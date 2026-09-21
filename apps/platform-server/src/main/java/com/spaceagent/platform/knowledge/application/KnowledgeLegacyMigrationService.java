package com.spaceagent.platform.knowledge.application;
import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit copy/rebuild, not a reassignment of old ownership or unproven vectors. */
@Service @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class KnowledgeLegacyMigrationService implements KnowledgeLegacyMigrationApi {
    private final KnowledgeAccessApplicationApi access;private final KnowledgeIndexCatalogRepository catalog;
    private final KnowledgeDocumentRepository documents;private final KnowledgeIndexIntakeApplicationApi intake;
    @org.springframework.beans.factory.annotation.Autowired private KnowledgeIndexIntakeRepository receipts;
    public KnowledgeLegacyMigrationService(KnowledgeAccessApplicationApi access,KnowledgeIndexCatalogRepository catalog,KnowledgeDocumentRepository documents,KnowledgeIndexIntakeApplicationApi intake){this.access=access;this.catalog=catalog;this.documents=documents;this.intake=intake;}
    public List<Item> inspect(KnowledgeBaseApplicationApi.Actor actor,String base,int offset,int limit){
        access.authorize(actor,base,KnowledgeBase.Permission.MANAGE);if(offset<0||offset>100000||limit<1||limit>100)throw invalid();
        return catalog.listDocuments(base,offset,limit).stream().filter(s->s.provenance().equals("LEGACY_PRIVATE")).map(s->documents.findById(s.documentId()).orElseThrow())
            .filter(d->Objects.equals(actor.userId(),d.ownerId()) && d.status()!=KnowledgeDocumentStatus.DELETED)
            .map(d->{var copied=receipts.latestLegacyCopy(actor.userId(),d.id()).orElse(null);return new Item(d.id(),d.name(),copied!=null?"COPY_SUBMITTED":d.storageLocation().startsWith("inline:")?"COPY_REBUILD_AVAILABLE":"SOURCE_UPLOAD_REQUIRED","LEGACY_VECTOR_PROVENANCE_UNKNOWN",
                copied==null?null:copied.baseId(),copied==null?null:copied.documentId(),copied==null?null:copied.jobId());}).toList();
    }
    public KnowledgeIndexIntakeApplicationApi.IntakeView copy(KnowledgeBaseApplicationApi.Actor actor,String legacyBase,String doc,String target,String space,String key){
        if(key==null || !key.matches("[A-Za-z0-9_.:-]{1,80}"))throw invalid();
        access.authorize(actor,legacyBase,KnowledgeBase.Permission.MANAGE);var targetBase=access.authorize(actor,target,KnowledgeBase.Permission.WRITE).base();
        if(targetBase.scope()!=KnowledgeBase.Scope.PERSONAL || !actor.userId().equals(targetBase.ownerId()))throw new BusinessException("Migration preserves personal ownership",HttpStatus.CONFLICT,"KNOWLEDGE_MIGRATION_PERSONAL_ONLY");
        catalog.findDocumentScope(doc).filter(s->s.baseId().equals(legacyBase)&&s.provenance().equals("LEGACY_PRIVATE")).orElseThrow(KnowledgeLegacyMigrationService::invalid);
        var source=documents.findById(doc).filter(d->Objects.equals(actor.userId(),d.ownerId()) && d.status()!=KnowledgeDocumentStatus.DELETED).orElseThrow(KnowledgeLegacyMigrationService::invalid);
        if(!source.storageLocation().startsWith("inline:"))throw new BusinessException("Upload original content or use the URL snapshot intake",HttpStatus.CONFLICT,"KNOWLEDGE_MIGRATION_SOURCE_REQUIRED");
        byte[] text=source.storageLocation().substring(7).getBytes(StandardCharsets.UTF_8);
        String copyKey=KnowledgeIndexBuildCoordinator.hash((doc+":"+key).getBytes(StandardCharsets.UTF_8));
        var copied=intake.file(new KnowledgeIndexIntakeApplicationApi.FileCommand(actor,target,null,0,source.name(),space,"text/plain",text,copyKey));
        receipts.linkLegacy(copied.generationId(),doc);return copied;
    }
    private static BusinessException invalid(){return new BusinessException("Invalid legacy migration source",HttpStatus.BAD_REQUEST,"KNOWLEDGE_MIGRATION_INVALID");}
}
