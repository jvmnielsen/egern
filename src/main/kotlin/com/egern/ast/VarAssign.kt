package com.egern.ast

import com.egern.visitor.Visitor

class VarAssign(
    val ids: List<String>,
    val indexExprs: List<ArrayIndexExpr>,
    val classFields: List<ClassField>,
    val expr: Expr,
    lineNumber: Int = -1,
    charPosition: Int = -1
) : Statement(lineNumber, charPosition) {
    override fun accept(visitor: Visitor) {
        visitor.preVisit(this)
        (indexExprs + classFields).forEach {
            it.accept(visitor)
            visitor.midVisit(this)
        }
        expr.accept(visitor)
        visitor.postVisit(this)
    }
}