package com.egern.codegen

enum class InstructionType(val instruction: String?) {
    MOV("movq"),
    ADD("addq"),
    SUB("subq"),
    INC("incq"),
    DEC("decq"),
    IMUL("imulq"),
    IDIV(null),
    MOD(null),
    CMP("cmpq"),
    JMP("jmp"),
    JNE("jne"),
    JE("je"),
    JG("jg"),
    JGE("jge"),
    JL("jl"),
    JLE("jle"),
    AND("andq"),
    OR("orq"),
    PUSH("pushq"),
    POP("popq"),
    CALL("call"),
    RET("ret"),
    LABEL(null),
    META(null)
}