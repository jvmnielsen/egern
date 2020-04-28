package com.egern.codegen

import com.egern.ast.*
import com.egern.labels.LabelGenerator
import com.egern.symbols.ClassDefinition
import com.egern.symbols.Symbol
import com.egern.symbols.SymbolTable
import com.egern.symbols.SymbolType
import com.egern.types.*
import com.egern.util.*
import com.egern.visitor.SymbolAwareVisitor
import kotlin.math.max
import kotlin.math.min

class CodeGenerationVisitor(
    symbolTable: SymbolTable,
    classDefinitions: List<ClassDefinition>
) : SymbolAwareVisitor(symbolTable, classDefinitions) {
    val instructions = mutableListOf<Instruction>()
    val dataFields = mutableListOf<String>()
    val staticStrings = mutableMapOf(
        "boolean_true" to "true",
        "boolean_false" to "false",
        "format_int" to "%d",
        "format_newline" to "",
        "format_string" to "%s"
    )
    private val functionStack = stackOf<FuncDecl>()
    private var currentClassDefinition: ClassDefinition? = null
    var vTableSize = 0

    companion object {
        // STACK FRAME - CONSTANT OFFSETS FROM RBP
        const val LOCAL_VAR_OFFSET = 2
        const val FUNCTION_POINTER_BITMAP_OFFSET = 1
        const val STATIC_LINK_OFFSET = -2
        const val PARAM_OFFSET = -3

        const val PARAMS_IN_REGISTERS = 6
    }

    private fun add(instruction: Instruction) {
        instructions.add(instruction)
    }

    private fun followStaticLink(diff: Int) {
        add(
            Instruction(
                InstructionType.MOV, InstructionArg(RBP, Direct), InstructionArg(StaticLink, Direct),
                comment = "Prepare to follow static link pointer"
            )
        )
        for (i in 0 until diff) {
            add(
                Instruction(
                    InstructionType.MOV,
                    InstructionArg(StaticLink, IndirectRelative(STATIC_LINK_OFFSET)),
                    InstructionArg(StaticLink, Direct),
                    comment = "Following static link pointer"
                )
            )
        }
    }

    private fun makeFunctionPointerBitmap(stmts: List<ASTNode>): String {
        return "0b0" + stmts.filterIsInstance<VarDecl>().map { isPointer(deriveType(it.expr)) }.joinToString("")
    }

    private fun makeClassPointerBitmap(fields: List<Any>): String {
        return "0b0" + fields.map {
            when (it) {
                is FieldDecl -> isPointer(deriveType(it.expr))
                is Pair<*, *> -> isPointer(it.second as ExprType)
                else -> throw Exception("Invalid field type")
            }
        }.joinToString("")
    }

    private fun isPointer(exprType: ExprType): Int {
        return when (exprType) {
            is ARRAY -> 1
            is CLASS -> 1
            else -> 0
        }
    }

    override fun preVisit(program: Program) {
        functionStack.push(null)
        populateDataSection()

        add(
            Instruction(
                InstructionType.LABEL,
                InstructionArg(MainLabel, Direct)
            )
        )
        add(
            Instruction(
                InstructionType.META,
                MetaOperation.CalleePrologue
            )
        )
        add(
            Instruction(
                InstructionType.META,
                MetaOperation.AllocateInternalHeap
            )
        )
        add(
            Instruction(
                InstructionType.META,
                MetaOperation.AllocateStackSpace,
                MetaOperationArg(program.variableCount)
            )
        )
        populateVTable()
        add(
            Instruction(
                InstructionType.META,
                MetaOperation.CalleeSave
            )
        )
    }

    private fun populateDataSection() {
        classDefinitions.forEach { classDef ->
            classDef.getLocalFields().forEach {
                val label = DataFieldGenerator.nextLabel(it.ids[0])
                dataFields.add(label)
                it.staticDataField = label
            }
        }
    }

    private fun populateVTable() {
        var currentOffset = 0

        add(
            Instruction(
                InstructionType.MOV,
                InstructionArg(VTable, Indirect),
                InstructionArg(Register(OpReg1), Direct),
                comment = "Store Vtable base address in register"
            )
        )

        classDefinitions.forEach {
            it.vTableOffset = currentOffset

            // Add methods
            it.getAllMethods().forEach { method ->
                add(
                    Instruction(
                        InstructionType.MOV,
                        InstructionArg(ImmediateValue(method.startLabel), Direct),
                        InstructionArg(Register(OpReg2), Direct),
                        comment = "Store address of function"
                    )
                )
                add(
                    Instruction(
                        InstructionType.MOV,
                        InstructionArg(Register(OpReg2), Direct),
                        InstructionArg(Register(OpReg1), IndirectRelative(-currentOffset)),
                        comment = "Add method to Vtable"
                    )
                )
                currentOffset++
            }
        }

        vTableSize = currentOffset
    }

    override fun midVisit(program: Program) {
        add(
            Instruction(
                InstructionType.PUSH,
                InstructionArg(ReturnValue, Direct),
                comment = "Save return value before program deallocation"
            )
        )
        add(
            Instruction(
                InstructionType.META,
                MetaOperation.DeallocateInternalHeap
            )
        )
        add(
            Instruction(
                InstructionType.POP,
                InstructionArg(ReturnValue, Direct),
                comment = "Restore return value"
            )
        )
        add(
            Instruction(
                InstructionType.JMP,
                InstructionArg(Memory("main_end"), Direct),
                comment = "Jump to end of program"
            )
        )
    }

    override fun postVisit(program: Program) {
        add(
            Instruction(
                InstructionType.LABEL,
                InstructionArg(Memory("main_end"), Direct)
            )
        )
        add(
            Instruction(
                InstructionType.META,
                MetaOperation.CalleeRestore
            )
        )
        add(
            Instruction(
                InstructionType.META,
                MetaOperation.CalleeEpilogue
            )
        )
    }

    override fun preVisit(funcDecl: FuncDecl) {
        symbolTable = funcDecl.symbolTable
        functionStack.push(funcDecl)
        val pointerBitmap = makeFunctionPointerBitmap(funcDecl.stmts)

        add(
            Instruction(
                InstructionType.LABEL,
                InstructionArg(Memory(funcDecl.startLabel), Direct)
            )
        )
        add(
            Instruction(
                InstructionType.META,
                MetaOperation.CalleePrologue
            )
        )
        add(
            Instruction(
                InstructionType.PUSH,
                InstructionArg(ImmediateValue(pointerBitmap), Direct),
                comment = "Push pointer bitmap"
            )
        )
        add(
            Instruction(
                InstructionType.META,
                MetaOperation.AllocateStackSpace,
                MetaOperationArg(funcDecl.variableCount)
            )
        )
        add(
            Instruction(
                InstructionType.META,
                MetaOperation.CalleeSave
            )
        )
    }

    override fun postVisit(funcDecl: FuncDecl) {
        symbolTable = funcDecl.symbolTable.parent!!
        functionStack.pop()
        add(
            Instruction(
                InstructionType.LABEL,
                InstructionArg(Memory(funcDecl.endLabel), Direct)
            )
        )
        add(
            Instruction(
                InstructionType.META,
                MetaOperation.CalleeRestore
            )
        )
        add(
            Instruction(
                InstructionType.META,
                MetaOperation.CalleeEpilogue
            )
        )
    }

    override fun preVisit(funcCall: FuncCall) {
        add(Instruction(InstructionType.META, MetaOperation.CallerSave))
    }

    override fun postVisit(funcCall: FuncCall) {
        val func = symbolTable.lookup(funcCall.id)!!
        val decl = func.info["funcDecl"] as FuncDecl
        val scopeDiff = symbolTable.scope - decl.symbolTable.scope
        val numArgs = funcCall.args.size

        passFunctionArgs(numArgs)

        if (scopeDiff < 0) {
            // Call is in nested func declaration
            add(Instruction(InstructionType.PUSH, InstructionArg(RBP, Direct), comment = "Push static link (inwards)"))
        } else {
            // Call is recursive or outwards
            followStaticLink(scopeDiff)
            // Find static link in parent
            // Follow static link is always 1 scope short of the scope we need when considering functions
            // We compare with the scope level of another function which is nested by at least 1
            add(
                Instruction(
                    InstructionType.PUSH,
                    InstructionArg(StaticLink, IndirectRelative(STATIC_LINK_OFFSET)),
                    comment = "Push static link (outwards)"
                )
            )
        }
        add(
            Instruction(
                InstructionType.CALL,
                InstructionArg(Memory(decl.startLabel), Direct),
                comment = "Call function"
            )
        )

        // Deallocate static link
        add(Instruction(InstructionType.META, MetaOperation.DeallocateStackSpace, MetaOperationArg(1)))
        functionEpilogue(numArgs)
    }

    private fun passFunctionArgs(numArgs: Int) {
        // Move first arguments (after caller saved registers) to registers
        for (index in (0 until min(PARAMS_IN_REGISTERS, numArgs))) {
            add(
                Instruction(
                    InstructionType.MOV,
                    InstructionArg(RSP, IndirectRelative(-numArgs + index + 1)),
                    InstructionArg(Register(ParamReg(index)), Direct),
                    comment = "Move argument to parameter register $index"
                )
            )
        }

        // Push remaining arguments to stack in reverse order
        for (index in (0 until numArgs - PARAMS_IN_REGISTERS)) {
            add(
                Instruction(
                    InstructionType.PUSH,
                    InstructionArg(
                        RSP,
                        IndirectRelative(-(2 * index))
                    ),
                    comment = "Push argument to stack"
                )
            )
        }
    }

    private fun functionEpilogue(numArgs: Int) {
        val parametersOnStack = max(numArgs - PARAMS_IN_REGISTERS, 0)
        add(
            Instruction(
                InstructionType.META,
                MetaOperation.DeallocateStackSpace,
                MetaOperationArg(parametersOnStack)
            )
        )
        add(Instruction(InstructionType.META, MetaOperation.DeallocateStackSpace, MetaOperationArg(numArgs)))
        add(Instruction(InstructionType.META, MetaOperation.CallerRestore))

        // Push return value to stack as function/method calls can be used as an expression
        add(Instruction(InstructionType.PUSH, InstructionArg(ReturnValue, Direct), comment = "Push return value"))
    }

    override fun preVisit(block: Block) {
        symbolTable = block.symbolTable
    }

    override fun postVisit(block: Block) {
        symbolTable = block.symbolTable.parent!!
    }

    override fun visit(voidExpr: VoidExpr) {
        add(
            Instruction(
                InstructionType.PUSH,
                InstructionArg(ImmediateValue("0"), Direct),
                comment = "Push static integer value (default return 0)"
            )
        )
    }

    override fun visit(intExpr: IntExpr) {
        add(
            Instruction(
                InstructionType.PUSH,
                InstructionArg(ImmediateValue(intExpr.value.toString()), Direct),
                comment = "Push static integer value"
            )
        )
    }

    override fun visit(stringExpr: StringExpr) {
        val label = DataFieldGenerator.nextLabel("string")
        staticStrings[label] = stringExpr.value
        stringExpr.dataLabel = label
        add(
            Instruction(
                InstructionType.PUSH,
                InstructionArg(ImmediateValue(stringExpr.dataLabel), Direct),
                comment = "Push static string value"
            )
        )
    }

    override fun visit(booleanExpr: BooleanExpr) {
        add(
            Instruction(
                InstructionType.PUSH,
                InstructionArg(ImmediateValue((if (booleanExpr.value) 1 else 0).toString()), Direct),
                comment = "Push static boolean value"
            )
        )
    }

    override fun visit(idExpr: IdExpr) {
        val idLocation = getIdLocation(idExpr.id, true)
        add(
            Instruction(
                InstructionType.PUSH,
                idLocation,
                comment = "Push value of ${idExpr.id} in scope"
            )
        )
    }

    private fun getIdLocation(id: String, checkDeclared: Boolean = false): InstructionArg {
        // Find static link address for scope containing given id
        val symbol = symbolTable.lookup(id, checkDeclared)
        if (symbol != null) {
            return if (symbol.type == SymbolType.Field) getMethodFieldLocation(symbol) else getStackLocation(symbol)
        }

        return getConstructorArgLocation(id) ?: throw Exception("Symbol $id is undefined")
    }

    private fun getStackLocation(symbol: Symbol): InstructionArg {
        /**
         * Get the location of a local variable or parameter from id
         * The id can be in the current scope or any parent scope of this,
         * so potentially a number of static links must first be followed to locate the correct stack frame
         *
         * For local variables and for the 7th+ parameter, the position is some offset from the static link register
         * First 6 parameters are for the current scope saved in registers
         * For any enclosing scope, they have been saved at the top of the relevant stack frame
         */

        val symbolOffset = when (symbol.type) {
            SymbolType.Variable -> symbol.info["variableOffset"]
            SymbolType.Parameter -> symbol.info["paramOffset"]
            else -> throw Exception("That's illegal - no higher order functions please")
        } as Int

        // Symbol is a parameter (1-6) in current scope - value is in register
        val scopeDiff = symbolTable.scope - symbol.scope
        if (scopeDiff == 0 && symbol.type == SymbolType.Parameter && symbolOffset < PARAMS_IN_REGISTERS) {
            return InstructionArg(Register(ParamReg(symbolOffset)), Direct)
        }

        // Get base pointer of scope containing symbol and find offset for symbol location
        followStaticLink(scopeDiff)
        val container = functionStack.peek(scopeDiff)
        val offset = when (symbol.type) {
            SymbolType.Variable -> symbolOffset + LOCAL_VAR_OFFSET
            SymbolType.Parameter -> when {
                // Param saved by caller after its local variables
                symbolOffset < PARAMS_IN_REGISTERS -> symbolOffset + LOCAL_VAR_OFFSET + container!!.variableCount
                // Calculate offset for params on stack (in non-reversed order)
                else -> PARAM_OFFSET - (symbolOffset - PARAMS_IN_REGISTERS)
            }
            else -> throw Exception("Invalid id ${symbol.id}")
        }

        return InstructionArg(StaticLink, IndirectRelative(offset))
    }

    private fun getMethodFieldLocation(symbol: Symbol): InstructionArg {
        val fieldOffset = currentClassDefinition!!.getFieldOffset(symbol.id)
        return InstructionArg(Register(ParamReg(0)), IndirectRelative(-(fieldOffset + 3)))
    }

    private fun getConstructorArgLocation(param: String): InstructionArg? {
        // All constructor args from all superclasses are on stack - get offset in this
        val constructor = currentClassDefinition?.getConstructor() ?: return null
        val paramOffset = constructor.indexOfFirst { it.first == param }

        return InstructionArg(Register(OpReg1), IndirectRelative(-(constructor.size - paramOffset - 1)))
    }

    override fun postVisit(compExpr: CompExpr) {
        // Pop expressions to register 1 and 2
        add(
            Instruction(
                InstructionType.POP,
                InstructionArg(Register(OpReg2), Direct),
                comment = "Pop expression to register 2"
            )
        )
        add(
            Instruction(
                InstructionType.POP,
                InstructionArg(Register(OpReg1), Direct),
                comment = "Pop expression to register 1"
            )
        )
        add(
            Instruction(
                InstructionType.CMP,
                InstructionArg(Register(OpReg2), Direct),
                InstructionArg(Register(OpReg1), Direct),
                comment = "Compare with ${compExpr.op.value}"
            )
        )
        val trueLabel = LabelGenerator.nextLabel("cmp_true")
        val endLabel = LabelGenerator.nextLabel("cmp_end")
        val jumpOperator = when (compExpr.op) {
            CompOp.EQ -> InstructionType.JE
            CompOp.NEQ -> InstructionType.JNE
            CompOp.LT -> InstructionType.JL
            CompOp.GT -> InstructionType.JG
            CompOp.LTE -> InstructionType.JLE
            CompOp.GTE -> InstructionType.JGE
        }
        add(Instruction(jumpOperator, InstructionArg(Memory(trueLabel), Direct), comment = "Jump if true"))
        add(
            Instruction(
                InstructionType.PUSH,
                InstructionArg(ImmediateValue("0"), Direct),
                comment = "Push false if comparison was false"
            )
        )
        add(
            Instruction(
                InstructionType.JMP,
                InstructionArg(Memory(endLabel), Direct),
                comment = "Skip pushing false if success"
            )
        )
        add(
            Instruction(
                InstructionType.LABEL,
                InstructionArg(Memory(trueLabel), Direct)
            )
        )
        add(
            Instruction(
                InstructionType.PUSH,
                InstructionArg(ImmediateValue("1"), Direct),
                comment = "Push true if comparison was true"
            )
        )
        add(
            Instruction(
                InstructionType.LABEL,
                InstructionArg(Memory(endLabel), Direct)
            )
        )
    }

    override fun postVisit(arithExpr: ArithExpr) {
        // Pop expressions to register 1 and 2 in reverse order
        add(
            Instruction(
                InstructionType.POP,
                InstructionArg(Register(OpReg2), Direct),
                comment = "Pop expression to register 2"
            )
        )
        add(
            Instruction(
                InstructionType.POP,
                InstructionArg(Register(OpReg1), Direct),
                comment = "Pop expression to register 1"
            )
        )
        val arithOperator = when (arithExpr.op) {
            ArithOp.PLUS -> InstructionType.ADD
            ArithOp.MINUS -> InstructionType.SUB
            ArithOp.TIMES -> InstructionType.IMUL
            ArithOp.DIVIDE -> InstructionType.IDIV
            ArithOp.MODULO -> InstructionType.MOD
        }
        add(
            Instruction(
                arithOperator,
                InstructionArg(Register(OpReg2), Direct),
                InstructionArg(Register(OpReg1), Direct),
                comment = "Do arithmetic operation"
            )
        )
        add(
            Instruction(
                InstructionType.PUSH,
                InstructionArg(Register(OpReg1), Direct),
                comment = "Push result to stack"
            )
        )
    }

    override fun postVisit(booleanOpExpr: BooleanOpExpr) {
        // Pop expressions to register 1 and 2 in reverse order
        if (booleanOpExpr.rhs != null) {
            add(
                Instruction(
                    InstructionType.POP,
                    InstructionArg(Register(OpReg2), Direct),
                    comment = "Pop expression to register 2"
                )
            )
        }
        add(
            Instruction(
                InstructionType.POP,
                InstructionArg(Register(OpReg1), Direct),
                comment = "Pop expression to register 1"
            )
        )
        val operator = when (booleanOpExpr.op) {
            BooleanOp.AND -> InstructionType.AND
            BooleanOp.OR -> InstructionType.OR
            BooleanOp.NOT -> InstructionType.NOT
        }
        if (booleanOpExpr.rhs != null) {
            add(
                Instruction(
                    operator,
                    InstructionArg(Register(OpReg2), Direct),
                    InstructionArg(Register(OpReg1), Direct),
                    comment = "Do boolean operation"
                )
            )
        } else {
            add(
                Instruction(
                    operator,
                    InstructionArg(Register(OpReg1), Direct),
                    comment = "Do boolean operation"
                )
            )
        }
        add(
            Instruction(
                InstructionType.PUSH,
                InstructionArg(Register(OpReg1), Direct),
                comment = "Push result to stack"
            )
        )
    }

    override fun postVisit(arrayExpr: ArrayExpr) {
        add(
            Instruction(
                InstructionType.META,
                MetaOperation.AllocateHeapSpace,
                MetaOperationArg(arrayExpr.entries.size + 2)
            )
        )

        val arrayLen = arrayExpr.entries.size
        add(
            Instruction(
                InstructionType.MOV,
                InstructionArg(ImmediateValue(arrayLen.toString()), Direct),
                InstructionArg(ReturnValue, Indirect),
                comment = "Write size information before array"
            )
        )

        val pointerBitmap = isPointer(deriveType(arrayExpr)).toString().repeat(arrayLen)
        add(
            Instruction(
                InstructionType.MOV,
                InstructionArg(ImmediateValue("0b0$pointerBitmap"), Direct),
                InstructionArg(ReturnValue, IndirectRelative(-1)),
                comment = "Write whether elements are references or not as a bitmap"
            )
        )

        for (index in arrayExpr.entries.indices) {
            add(
                Instruction(
                    InstructionType.POP,
                    InstructionArg(Register(OpReg1), Direct),
                    comment = "Pop expression to register 1"
                )
            )
            add(
                Instruction(
                    InstructionType.MOV,
                    InstructionArg(Register(OpReg1), Direct),
                    InstructionArg(ReturnValue, IndirectRelative(-(arrayLen - index + 1))),
                    comment = "Move expression to array index $index"
                )
            )
        }
        add(
            Instruction(
                InstructionType.PUSH,
                InstructionArg(ReturnValue, Direct),
                comment = "Push result to stack"
            )
        )
    }

    // Indexes into array, result is in OpReg2
    private fun indexIntoArray(arrayIndexExpr: ArrayIndexExpr) {
        add(
            Instruction(
                InstructionType.POP,
                InstructionArg(Register(OpReg2), Direct),
                comment = "Move array pointer to data register"
            )
        )
        for ((index, _) in arrayIndexExpr.indices.withIndex()) {
            add(
                Instruction(
                    InstructionType.ADD,
                    InstructionArg(ImmediateValue("16"), Direct),
                    InstructionArg(Register(OpReg2), Direct),
                    comment = "Move past array info"
                )
            )
            add(
                Instruction(
                    InstructionType.POP,
                    InstructionArg(Register(OpReg1), Direct),
                    comment = "Pop expression to register 1"
                )
            )
            add(
                Instruction(
                    InstructionType.IMUL,
                    InstructionArg(ImmediateValue("8"), Direct),
                    InstructionArg(Register(OpReg1), Direct),
                    comment = "Multiply index with 8"
                )
            )
            add(
                Instruction(
                    InstructionType.ADD,
                    InstructionArg(Register(OpReg1), Direct),
                    InstructionArg(Register(OpReg2), Direct),
                    comment = "Move pointer by index"
                )
            )
            if (index < arrayIndexExpr.indices.size - 1) {
                add(
                    Instruction(
                        InstructionType.MOV,
                        InstructionArg(Register(OpReg2), Indirect),
                        InstructionArg(Register(OpReg2), Direct),
                        comment = "Follow pointer"
                    )
                )
            }
        }
        // Only follow last pointer if we want to return a value
        if (!arrayIndexExpr.reference) {
            add(
                Instruction(
                    InstructionType.MOV,
                    InstructionArg(Register(OpReg2), Indirect),
                    InstructionArg(Register(OpReg2), Direct),
                    comment = "Follow pointer"
                )
            )
        }
    }

    override fun postVisit(arrayIndexExpr: ArrayIndexExpr) {
        indexIntoArray(arrayIndexExpr)
        add(
            Instruction(
                InstructionType.PUSH,
                InstructionArg(Register(OpReg2), Direct),
                comment = "Push value to stack"
            )
        )
    }

    override fun postVisit(lenExpr: LenExpr) {
        add(
            Instruction(
                InstructionType.POP,
                InstructionArg(Register(OpReg1), Direct),
                comment = "Pop array reference from stack"
            )
        )
        add(
            Instruction(
                InstructionType.PUSH,
                InstructionArg(Register(OpReg1), Indirect),
                comment = "Push array length to stack"
            )
        )
    }

    override fun preVisit(classDecl: ClassDecl) {
        currentClassDefinition = classDefinitions.find { classDecl.id == it.className }
        symbolTable = currentClassDefinition!!.symbolTable
    }

    override fun midVisit(classDecl: ClassDecl) {
        add(
            Instruction(
                InstructionType.JMP,
                InstructionArg(Memory(classDecl.endLabel), Direct),
                comment = "Jump to end of class"
            )
        )
    }

    override fun postVisit(classDecl: ClassDecl) {
        symbolTable = symbolTable.parent!!
        add(
            Instruction(
                InstructionType.LABEL,
                InstructionArg(Memory(classDecl.endLabel), Direct)
            )
        )
    }

    override fun postVisit(objectInstantiation: ObjectInstantiation) {
        val classDefinition = classDefinitions.find { it.className == objectInstantiation.classId }!!
        val allFields = classDefinition.getAllFields()
        var heapOffset = 0

        add(
            Instruction(
                InstructionType.META,
                MetaOperation.AllocateHeapSpace,
                MetaOperationArg(allFields.size + 3)
            )
        )

        // Move class pointer to first object field
        val vTableOffset = classDefinition.vTableOffset
        add(
            Instruction(
                InstructionType.MOV,
                InstructionArg(VTable, Indirect),
                InstructionArg(Register(OpReg1), Direct),
                comment = "Get pointer to vtable"
            )
        )
        add(
            Instruction(
                InstructionType.MOV,
                InstructionArg(Register(OpReg1), IndirectRelative(-vTableOffset)),
                InstructionArg(Register(OpReg1), Direct),
                comment = "Get pointer to entry for class '${objectInstantiation.classId}' in vtable"
            )
        )
        add(
            Instruction(
                InstructionType.MOV,
                InstructionArg(Register(OpReg1), Direct),
                InstructionArg(ReturnValue, Indirect),
                comment = "Save class pointer at beginning of object in heap"
            )
        )
        heapOffset++

        // Insert information about number of fields
        add(
            Instruction(
                InstructionType.MOV,
                InstructionArg(ImmediateValue(allFields.size.toString()), Direct),
                InstructionArg(Register(OpReg2), Direct),
                comment = "Store number of fields for class"
            )
        )
        add(
            Instruction(
                InstructionType.MOV,
                InstructionArg(Register(OpReg2), Direct),
                InstructionArg(ReturnValue, IndirectRelative(-heapOffset)),
                comment = "Add size information to object"
            )
        )
        heapOffset++

        // Add pointer bitmap
        val pointerBitmap = makeClassPointerBitmap(allFields)
        add(
            Instruction(
                InstructionType.MOV,
                InstructionArg(ImmediateValue(pointerBitmap), Direct),
                InstructionArg(Register(OpReg2), Direct),
                comment = "Store pointer bitmap of class fields"
            )
        )
        add(
            Instruction(
                InstructionType.MOV,
                InstructionArg(Register(OpReg2), Direct),
                InstructionArg(ReturnValue, IndirectRelative(-heapOffset)),
                comment = "Add pointer bitmap to object"
            )
        )
        heapOffset++


        // Visit all superclass args, pushing results to stack
        currentClassDefinition = classDefinition
        while (currentClassDefinition != null) {
            // Save "base pointer" for current class constructor
            add(
                Instruction(
                    InstructionType.MOV,
                    InstructionArg(RSP, Direct),
                    InstructionArg(Register(OpReg1), Direct),
                    comment = "Save constructor argument pointer"
                )
            )

            currentClassDefinition!!.superclassArgs?.forEach {
                it.accept(this)
            }
            currentClassDefinition = currentClassDefinition?.superclass
        }

        // Move all constructor args to object
        val numArgs = classDefinition.getNumConstructorArgsPerClass()
        val fieldsPerClass = classDefinition.getLocalFieldsPerClass()
        repeat(numArgs.size) { classNum ->
            val numConstructorArgs = numArgs[classNum]
            repeat(numConstructorArgs) { index ->
                add(
                    Instruction(
                        InstructionType.POP,
                        InstructionArg(Register(OpReg1), Direct),
                        comment = "Pop expression result to register 1"
                    )
                )
                add(
                    Instruction(
                        InstructionType.MOV,
                        InstructionArg(Register(OpReg1), Direct),
                        InstructionArg(ReturnValue, IndirectRelative(-(heapOffset + numConstructorArgs - index - 1))),
                        comment = "Save value at object's constructor field ${index + 1}"
                    )
                )
            }

            heapOffset += numConstructorArgs

            // Move local class fields to object
            val localFields = fieldsPerClass[classNum]
            var fieldOffset = 0
            localFields.forEach { fieldDecl ->
                fieldDecl.ids.forEach { _ ->
                    add(
                        Instruction(
                            InstructionType.MOV,
                            InstructionArg(Memory(fieldDecl.staticDataField), Indirect),
                            InstructionArg(Register(OpReg1), Direct),
                            comment = "Get field value from data section"
                        )
                    )
                    add(
                        Instruction(
                            InstructionType.MOV,
                            InstructionArg(Register(OpReg1), Direct),
                            InstructionArg(ReturnValue, IndirectRelative(-(heapOffset + fieldOffset))),
                            comment = "Save value at object's constructor field ${fieldOffset + 1}"
                        )
                    )
                    fieldOffset++
                }
            }

            heapOffset += fieldOffset
        }

        add(
            Instruction(
                InstructionType.PUSH,
                InstructionArg(ReturnValue, Direct),
                comment = "Push object location to stack"
            )
        )
    }

    override fun preVisit(methodCall: MethodCall) {
        add(Instruction(InstructionType.META, MetaOperation.CallerSave))
    }

    override fun postVisit(methodCall: MethodCall) {
        // VTable lookup
        val objectClass = getObjectClass(methodCall.objectId)
        val classDefinition = classDefinitions.find { objectClass.className == it.className }!!
        val vTablePointer = classDefinition.vTableOffset

        // Find latest override of method
        val methodOffset = classDefinition.getAllMethods(objectClass.castTo ?: objectClass.className).indexOfLast {
            it.id == methodCall.methodId
        }
        val numArgs = methodCall.args.size

        passFunctionArgs(numArgs)

        add(
            Instruction(
                InstructionType.MOV,
                InstructionArg(VTable, Indirect),
                InstructionArg(Register(OpReg1), Direct),
                comment = "Move Vtable pointer to register"
            )
        )
        add(
            Instruction(
                InstructionType.CALL,
                InstructionArg(Register(OpReg1), IndirectRelative(-(vTablePointer + methodOffset))),
                comment = "Call method"
            )
        )
        functionEpilogue(numArgs)
    }


    override fun visit(thisExpr: ThisExpr) {
        add(Instruction(InstructionType.PUSH, getIdLocation(thisExpr.objectId), comment = "Push object reference"))
    }

    override fun visit(classField: ClassField) {
        val objectClass = getObjectClass(classField.objectId)
        val classDefinition = classDefinitions.find { objectClass.className == it.className }!!
        val fieldOffset = classDefinition.getFieldOffset(classField.fieldId, objectClass.castTo)
        val objectPointer = getIdLocation(classField.objectId)

        add(
            Instruction(
                InstructionType.MOV,
                objectPointer,
                InstructionArg(Register(OpReg1), Direct),
                comment = "Store object pointer in register"
            )
        )
        add(
            Instruction(
                InstructionType.ADD,
                InstructionArg(ImmediateValue("${8 * (fieldOffset + 3)}"), Direct),
                InstructionArg(Register(OpReg1), Direct),
                comment = "Store address of field"
            )
        )
        add(
            Instruction(
                InstructionType.PUSH,
                InstructionArg(Register(OpReg1), if (classField.reference) Direct else Indirect),
                comment = "Push field ${if (classField.reference) "reference" else "value"} to stack"
            )
        )
    }

    override fun visit(staticClassField: StaticClassField) {
        val classDefinition = classDefinitions.find { staticClassField.classId == it.className }!!
        val fieldDecl = classDefinition.getAllLocalFields().findLast { staticClassField.fieldId in it.ids }!!
        add(
            Instruction(
                InstructionType.PUSH,
                InstructionArg(Memory(fieldDecl.staticDataField), if (staticClassField.reference) Direct else Indirect),
                comment = "Push field ${if (staticClassField.reference) "reference" else "value"} to stack"
            )
        )
    }

    override fun preVisit(staticMethodCall: StaticMethodCall) {
        add(Instruction(InstructionType.META, MetaOperation.CallerSave))
    }

    override fun postVisit(staticMethodCall: StaticMethodCall) {
        // VTable lookup
        val classDefinition = classDefinitions.find { staticMethodCall.classId == it.className }!!
        val vTablePointer = classDefinition.vTableOffset

        // Find latest override of method
        val methodOffset = classDefinition.getAllMethods().indexOfLast {
            it.id == staticMethodCall.methodId
        }
        val numArgs = staticMethodCall.args.size

        passFunctionArgs(numArgs)

        add(
            Instruction(
                InstructionType.MOV,
                InstructionArg(VTable, Indirect),
                InstructionArg(Register(OpReg1), Direct),
                comment = "Move Vtable pointer to register"
            )
        )
        add(
            Instruction(
                InstructionType.CALL,
                InstructionArg(Register(OpReg1), IndirectRelative(-(vTablePointer + methodOffset))),
                comment = "Call method"
            )
        )
        functionEpilogue(numArgs)
    }

    override fun postVisit(printStmt: PrintStmt) {
        val type = if (printStmt.expr != null) deriveType(printStmt.expr).type else ExprTypeEnum.VOID

        // Special case for booleans, we want to print 'true' or 'false'
        if (type == ExprTypeEnum.BOOLEAN) {
            val trueLabel = LabelGenerator.nextLabel("print_true")
            val endLabel = LabelGenerator.nextLabel("print_end")

            add(
                Instruction(
                    InstructionType.POP,
                    InstructionArg(Register(OpReg1), Direct),
                    comment = "Pop expression (comparison result) into register"
                )
            )
            add(
                Instruction(
                    InstructionType.MOV,
                    InstructionArg(ImmediateValue("1"), Direct),
                    InstructionArg(Register(OpReg2), Direct),
                    comment = "Move true to other register"
                )
            )
            add(
                Instruction(
                    InstructionType.CMP,
                    InstructionArg(Register(OpReg1), Direct),
                    InstructionArg(Register(OpReg2), Direct),
                    comment = "Check if comparison result was true"
                )
            )
            add(
                Instruction(
                    InstructionType.JE,
                    InstructionArg(Memory(trueLabel), Direct),
                    comment = "If true, jump to position where true is pushed"
                )
            )

            add(
                Instruction(
                    InstructionType.PUSH,
                    InstructionArg(ImmediateValue("boolean_false"), Direct),
                    comment = "Push static string value 'false'"
                )
            )

            add(
                Instruction(
                    InstructionType.JMP,
                    InstructionArg(Memory(endLabel), Direct),
                    comment = "Skip past true section"
                )
            )

            add(
                Instruction(
                    InstructionType.LABEL,
                    InstructionArg(Memory(trueLabel), Direct)
                )
            )

            add(
                Instruction(
                    InstructionType.PUSH,
                    InstructionArg(ImmediateValue("boolean_true"), Direct),
                    comment = "Push static string value 'true'"
                )
            )

            add(
                Instruction(
                    InstructionType.LABEL,
                    InstructionArg(Memory(endLabel), Direct)
                )
            )
        }

        add(Instruction(InstructionType.META, MetaOperation.CallerSave))
        add(
            Instruction(
                InstructionType.META,
                MetaOperation.Print,
                MetaOperationArg(type.ordinal)
            )
        )
        add(Instruction(InstructionType.META, MetaOperation.CallerRestore))

        // Only remove expression if not empty
        if (type != ExprTypeEnum.VOID) {
            add(
                Instruction(
                    InstructionType.ADD, InstructionArg(ImmediateValue("8"), Direct),
                    InstructionArg(RSP, Direct),
                    comment = "Remove pushed expression"
                )
            )
        }
    }

    override fun postVisit(returnStmt: ReturnStmt) {
        if (returnStmt.expr != null) {
            add(
                Instruction(
                    InstructionType.POP,
                    InstructionArg(ReturnValue, Direct),
                    comment = "Pop expression to return value register"
                )
            )
        }
        val endLabel = functionStack.peek()?.endLabel
        if (endLabel != null) {
            add(
                Instruction(
                    InstructionType.JMP,
                    InstructionArg(Memory(endLabel), Direct),
                    comment = "Jump to end of function"
                )
            )
        }
    }

    override fun preMidVisit(ifElse: IfElse) {
        chooseBranch(ifElse.elseLabel)
    }

    override fun postMidVisit(ifElse: IfElse) {
        add(
            Instruction(
                InstructionType.JMP,
                InstructionArg(Memory(ifElse.endLabel), Direct),
                comment = "Skip else part if successful"
            )
        )
        add(
            Instruction(
                InstructionType.LABEL,
                InstructionArg(Memory(ifElse.elseLabel), Direct)
            )
        )
    }

    override fun postVisit(ifElse: IfElse) {
        add(
            Instruction(
                InstructionType.LABEL,
                InstructionArg(Memory(ifElse.endLabel), Direct)
            )
        )
    }

    override fun preVisit(whileLoop: WhileLoop) {
        add(
            Instruction(
                InstructionType.LABEL,
                InstructionArg(Memory(whileLoop.startLabel), Direct)
            )
        )
    }

    override fun midVisit(whileLoop: WhileLoop) {
        chooseBranch(whileLoop.endLabel)
    }

    override fun postVisit(whileLoop: WhileLoop) {
        add(
            Instruction(
                InstructionType.JMP,
                InstructionArg(Memory(whileLoop.startLabel), Direct),
                comment = "Jump back to continue loop"
            )
        )
        add(
            Instruction(
                InstructionType.LABEL,
                InstructionArg(Memory(whileLoop.endLabel), Direct)
            )
        )
    }

    private fun chooseBranch(label: String) {
        add(
            Instruction(
                InstructionType.POP,
                InstructionArg(Register(OpReg1), Direct),
                comment = "Pop expression to register"
            )
        )
        add(
            Instruction(
                InstructionType.MOV,
                InstructionArg(ImmediateValue("1"), Direct),
                InstructionArg(Register(OpReg2), Direct),
                comment = "Move true to other register"
            )
        )
        add(
            Instruction(
                InstructionType.CMP,
                InstructionArg(Register(OpReg2), Direct),
                InstructionArg(Register(OpReg1), Direct),
                comment = "Compare the expression to true"
            )
        )
        add(
            Instruction(
                InstructionType.JL,
                InstructionArg(Memory(label), Direct),
                comment = "Jump if false"
            )
        )
    }

    override fun visit(continueStmt: ContinueStmt) {
        add(
            Instruction(
                InstructionType.JMP,
                InstructionArg(Memory(continueStmt.jumpLabel), Direct),
                comment = "Jump to loop start"
            )
        )
    }

    override fun visit(breakStmt: BreakStmt) {
        add(
            Instruction(
                InstructionType.JMP,
                InstructionArg(Memory(breakStmt.jumpLabel), Direct),
                comment = "Jump to loop end"
            )
        )
    }

    override fun postVisit(varDecl: VarDecl) {
        // First declaration of variable in this scope
        varDecl.ids.forEach { symbolTable.lookup(it)?.isDeclared = true }
        variableAssignment(varDecl.ids)
    }

    override fun postVisit(fieldDecl: FieldDecl) {
        add(
            Instruction(
                InstructionType.POP,
                InstructionArg(Memory(fieldDecl.staticDataField), Indirect),
                comment = "Pop expression result to static data field"
            )
        )
    }

    override fun postVisit(varAssign: VarAssign) {
        variableAssignment(varAssign.ids, varAssign.indexExprs, varAssign.classFields)
    }

    private fun variableAssignment(
        ids: List<String>,
        arrayIds: List<ArrayIndexExpr> = emptyList(),
        classFields: List<ClassField> = emptyList()
    ) {
        // Find each variable/parameter location and set their value to the expression result
        add(Instruction(InstructionType.POP, InstructionArg(Register(OpReg1), Direct), comment = "Expression result"))
        val symbols = ids.map { symbolTable.lookup(it)!! }
        for (symbol in symbols) {
            val idLocation = getIdLocation(symbol.id)
            add(
                Instruction(
                    InstructionType.MOV,
                    InstructionArg(Register(OpReg1), Direct),
                    idLocation,
                    comment = "Set value of ${symbol.type.toString().toLowerCase()} ${symbol.id} to expression result"
                )
            )
        }
        for (id in arrayIds + classFields) {
            val name = when (id) {
                is ClassField -> id.fieldId
                is ArrayIndexExpr -> id.id
                else -> throw Exception("Invalid ID type")
            }
            add(
                Instruction(
                    InstructionType.POP,
                    InstructionArg(Register(OpReg2), Direct),
                    comment = "Pop pointer into register 2"
                )
            )
            add(
                Instruction(
                    InstructionType.MOV,
                    InstructionArg(Register(OpReg1), Direct),
                    InstructionArg(Register(OpReg2), Indirect),
                    comment = "Set value of $name at index to expression result"
                )
            )
        }
    }
}
