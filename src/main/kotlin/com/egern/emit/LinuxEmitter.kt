package com.egern.emit

import com.egern.codegen.*

class LinuxEmitter(instructions: List<Instruction>, syntax: SyntaxManager) :
    Emitter(instructions, AsmStringBuilder("#"), syntax) {

    override fun emitProgramPrologue() {
        builder
            .addLine(".data")
            .addLine("format_int:")
            .addLine(".string \"%d\\n\"", comment = "Integer format string for C printf")
            .addLine("format_newline:")
            .addLine(".string \"\\n\"", comment = "Empty format string for C printf")
            .newline()
            .addLine(".text")
            .addLine(".globl", Pair("main", null))
            .newline()
    }

    override fun emitProgramEpilogue() {
        // Empty epilogue
    }

    override fun emitPrint(arg: MetaOperationArg) {
        val empty = arg.value == 0
        builder
            .newline()
            .addLine("# PRINTING USING PRINTF")
            .addLine(
                "movq", Pair("\$format_${if (empty) "newline" else "int"}", "%rdi"),
                "Pass 1st argument in %rdi"
            )
        if (!empty) {
            builder.addLine(
                "movq", Pair("${8 * CALLER_SAVE_REGISTERS.size}(%rsp)", "%rsi"),
                "Pass 2nd argument in %rsi"
            )
        }
        builder
            .addLine("xor", Pair("%rax", "%rax"), "No floating point registers used")
            .addLine("call", Pair("printf", null), "Call function printf")
    }

    override fun emitMainLabel(): String {
        return "main"
    }
}
