DO $artifact_object_reference_constraints$ BEGIN
 IF to_regclass('public.platform_artifact_objects') IS NULL THEN RETURN; END IF;
 ALTER TABLE platform_artifact_objects DROP CONSTRAINT ck_artifact_object_refs;
 ALTER TABLE platform_artifact_objects ADD CONSTRAINT ck_artifact_object_refs CHECK(storage_reference~'^artifact-object:[A-Za-z0-9_.:-]+$' AND length(storage_reference)<=316 AND encryption_reference~'^tenant-key:[A-Za-z0-9_.:-]+$' AND length(encryption_reference)<=311);
 ALTER TABLE platform_artifact_object_staging DROP CONSTRAINT ck_artifact_staging_refs;
 ALTER TABLE platform_artifact_object_staging ADD CONSTRAINT ck_artifact_staging_refs CHECK(encryption_reference~'^tenant-key:[A-Za-z0-9_.:-]+$' AND length(encryption_reference)<=311 AND (staging_reference IS NULL OR (staging_reference~'^artifact-staging:[A-Za-z0-9_.:-]+$' AND length(staging_reference)<=317)));
END $artifact_object_reference_constraints$;
