package com.spaceagent.platform.inference.domain;
public class InferenceBudgetLimitException extends RuntimeException{private final String code;public InferenceBudgetLimitException(String c){super(c);code=c;}public String code(){return code;}}
