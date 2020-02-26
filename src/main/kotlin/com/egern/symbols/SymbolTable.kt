package com.egern.symbols

import com.egern.error.ErrorLogger
import java.lang.Exception

class SymbolTable(val scope: Int, val parent: SymbolTable?) {
    private val symbols: MutableMap<String, Symbol<*>> = mutableMapOf()

    fun insert(id: String, sym: Symbol<*>) {
        if (id in symbols) {
            ErrorLogger.log(Exception("Symbol $id of type ${symbols[id]?.type} has already been declared!"))
        }
        symbols[id] = sym
    }

    fun lookup(id: String): Symbol<*>? {
        return if (id in symbols) {
            symbols[id]
        } else {
            parent?.lookup(id)
        }
    }
}