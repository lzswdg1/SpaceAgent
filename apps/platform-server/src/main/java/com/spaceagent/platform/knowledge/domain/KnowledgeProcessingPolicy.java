package com.spaceagent.platform.knowledge.domain;

public record KnowledgeProcessingPolicy(Strategy strategy,int size,int overlap) {
    public enum Strategy {FIXED_CHARACTER,RECURSIVE_TOKEN,MARKDOWN_SECTION}
    public KnowledgeProcessingPolicy {
        if(strategy==null || size<64 || size>2048 || overlap<0 || overlap>size/2 || strategy==Strategy.FIXED_CHARACTER && size<100)throw new IllegalArgumentException("Invalid chunking policy");
    }
    public static KnowledgeProcessingPolicy defaults(){return new KnowledgeProcessingPolicy(Strategy.RECURSIVE_TOKEN,512,64);}
    public static KnowledgeProcessingPolicy legacy(){return new KnowledgeProcessingPolicy(Strategy.FIXED_CHARACTER,800,100);}
    public String fingerprint(){
        if(equals(legacy()))return digest("FIXED_CHARACTER:800:100");
        return digest("LC4J_1.19.2_COMMONMARK_0.24.0_O200K_V1:"+strategy+":"+size+":"+overlap);
    }
    private static String digest(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
