ALTER TABLE platform_project_plan_wave_claims
    ADD COLUMN job_id UUID;

ALTER TABLE platform_project_plan_wave_claims
    ADD CONSTRAINT fk_project_plan_wave_claim_job
        FOREIGN KEY(job_id) REFERENCES platform_project_coding_jobs(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX uk_project_plan_wave_claim_job
    ON platform_project_plan_wave_claims(job_id)
    WHERE job_id IS NOT NULL;
