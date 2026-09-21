DO $artifact_staging_upload_state$ BEGIN
 IF to_regclass('public.platform_artifact_object_staging') IS NULL THEN RETURN; END IF;
 ALTER TABLE platform_artifact_object_staging DROP CONSTRAINT ck_artifact_staging_state;
 ALTER TABLE platform_artifact_object_staging ADD CONSTRAINT ck_artifact_staging_state CHECK(state IN('OPEN','UPLOADING','VERIFIED','PUBLISHED','REJECTED','UNKNOWN') AND revision>0 AND expected_bytes BETWEEN 0 AND 5368709120 AND ((state='OPEN' AND staging_reference IS NULL) OR (state<>'OPEN' AND staging_reference IS NOT NULL)) AND ((state='PUBLISHED')=(published_object_id IS NOT NULL)));
END $artifact_staging_upload_state$;
