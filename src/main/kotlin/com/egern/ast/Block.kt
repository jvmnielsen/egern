package com.egern.ast

import com.egern.symbols.SymbolTable
import com.egern.visitor.Visitor

class Block(
    var stmts: List<ASTNode>,
    lineNumber: Int = -1,
    charPosition: Int = -1
) : ASTNode(lineNumber, charPosition) {
    lateinit var symbolTable: SymbolTable

    override fun accept(visitor: Visitor) {
        visitor.preVisit(this)
        stmts.forEach {
            visitor.preStmtVisit()
            it.accept(visitor)
            visitor.postStmtVisit()
        }
        visitor.postVisit(this)
    }
}
