package com.spaceagent.platform.knowledge.infrastructure;

import com.spaceagent.platform.knowledge.application.KnowledgeIndexWorker;
import com.spaceagent.platform.knowledge.application.KnowledgeIndexMaintenanceWorker;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

/** Same business owners and database, but only Knowledge background jobs are scheduled in this role. */
@Configuration @ConditionalOnProperty(name="platform.runtime.role",havingValue="knowledge-worker")
public class KnowledgeWorkerRoleConfiguration {
    @Bean static BeanFactoryPostProcessor knowledgeOnlySchedules(){return factory->{
        var registry=(org.springframework.beans.factory.support.BeanDefinitionRegistry)factory;
        registry.removeBeanDefinition(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME);
        registry.registerBeanDefinition(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME,
            new org.springframework.beans.factory.support.RootBeanDefinition(KnowledgeOnlySchedules.class));
    };}
    public static class KnowledgeOnlySchedules extends ScheduledAnnotationBeanPostProcessor {
        @Override public Object postProcessAfterInitialization(Object bean,String name){
            if(bean instanceof KnowledgeIndexWorker || bean instanceof KnowledgeIndexMaintenanceWorker || bean instanceof KnowledgeRagMetrics)
                return super.postProcessAfterInitialization(bean,name);
            return bean;
        }
    }
}
