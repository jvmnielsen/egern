package com.egern.ast

import com.egern.visitor.Visitor

class IfElse(val expression: Expr, val ifBlock: Block, val elseBlock: Block?) : Statement() {
    override fun accept(visitor: Visitor) {
        visitor.preVisit(this)
        expression.accept(visitor)
        visitor.preMidVisit(this)
        ifBlock.accept(visitor)
        if (elseBlock != null) {
            visitor.postMidVisit(this)
            elseBlock.accept(visitor)
        }
        visitor.postVisit(this)
    }
}