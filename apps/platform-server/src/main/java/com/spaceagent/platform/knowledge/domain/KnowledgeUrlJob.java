package com.spaceagent.platform.knowledge.domain;

import java.net.IDN;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/** Knowledge-owned refreshable URL ingestion definition. */
public record KnowledgeUrlJob(
        String id,
        String tenantId,
        String ownerId,
        String knowledgeDocumentId,
        String normalizedUrl,
        String origin,
        RefreshPolicy refreshPolicy,
        State state,
        long revision,
        Instant nextRefreshAt,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt) {

    public KnowledgeUrlJob {
        require(id,"id");require(tenantId,"tenantId");require(ownerId,"ownerId");
        require(knowledgeDocumentId,"knowledgeDocumentId");
        String canonical=normalize(normalizedUrl);
        if(!canonical.equals(normalizedUrl))throw new IllegalArgumentException("normalizedUrl is not canonical");
        if(!origin.equals(origin(normalizedUrl)))throw new IllegalArgumentException("origin does not match URL");
        Objects.requireNonNull(refreshPolicy,"refreshPolicy");Objects.requireNonNull(state,"state");
        Objects.requireNonNull(createdAt,"createdAt");Objects.requireNonNull(updatedAt,"updatedAt");
        if(revision<=0||state==State.ACTIVE&&nextRefreshAt==null
                ||state==State.ARCHIVED&&archivedAt==null
                ||state!=State.ARCHIVED&&archivedAt!=null)throw new IllegalArgumentException("URL job lifecycle is invalid");
    }

    public static String normalize(String value){
        try{
            if(value==null||value.isBlank()||value.length()>2048)throw new IllegalArgumentException();
            URI input=URI.create(value.trim());
            if(!"https".equalsIgnoreCase(input.getScheme())||input.getHost()==null||input.getUserInfo()!=null
                    ||input.getFragment()!=null)throw new IllegalArgumentException();
            String host=IDN.toASCII(input.getHost()).toLowerCase(Locale.ROOT);
            if(host.equals("localhost")||host.endsWith(".localhost")||host.matches("[0-9.]+")
                    ||host.contains(":"))throw new IllegalArgumentException();
            int port=input.getPort()==443?-1:input.getPort();
            if(port!=-1)throw new IllegalArgumentException();
            String path=input.getRawPath()==null||input.getRawPath().isBlank()?"/":input.normalize().getRawPath();
            URI normalized=new URI("https",null,host,-1,path,input.getRawQuery(),null);
            String result=normalized.toASCIIString();if(result.length()>2048)throw new IllegalArgumentException();return result;
        }catch(Exception e){throw new IllegalArgumentException("Knowledge URL must be canonical public HTTPS",e);}
    }

    public static String origin(String url){URI value=URI.create(url);return "https://"+value.getHost().toLowerCase(Locale.ROOT);}
    public enum State{DRAFT,ACTIVE,PAUSED,ARCHIVED}
    public enum RefreshMode{MANUAL,PERIODIC}
    public record RefreshPolicy(RefreshMode mode,int intervalSeconds,boolean useConditionalRequests,int maximumRedirects,int maximumBytes){
        public RefreshPolicy{Objects.requireNonNull(mode);if(mode==RefreshMode.MANUAL&&intervalSeconds!=0||mode==RefreshMode.PERIODIC&&(intervalSeconds<300||intervalSeconds>2_592_000)||maximumRedirects<0||maximumRedirects>5||maximumBytes<1||maximumBytes>10_000_000)throw new IllegalArgumentException("URL refresh policy is invalid");}
    }
    private static void require(String v,String f){if(v==null||v.isBlank()||v.length()>160)throw new IllegalArgumentException(f+" is invalid");}
}
