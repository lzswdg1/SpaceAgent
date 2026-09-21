package com.spaceagent.platform.project.infrastructure;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix="platform.workspace") public class WorkspaceProperties{
 private String managedRoot=System.getProperty("java.io.tmpdir")+"/spaceagent-managed-workspaces";private int gitTimeoutSeconds=120;
 public String getManagedRoot(){return managedRoot;}public void setManagedRoot(String v){managedRoot=v;}public int getGitTimeoutSeconds(){return gitTimeoutSeconds;}public void setGitTimeoutSeconds(int v){gitTimeoutSeconds=v;}
}
