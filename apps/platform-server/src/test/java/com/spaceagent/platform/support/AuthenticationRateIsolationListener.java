package com.spaceagent.platform.support;

import com.spaceagent.platform.integration.infrastructure.PlatformRequestAdmissionService;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;

/** Test-only: cached Spring contexts must not share prior tests' minute-window auth attempts. */
public class AuthenticationRateIsolationListener extends AbstractTestExecutionListener {
    @Override public void beforeTestMethod(TestContext context){
        for(var admission:context.getApplicationContext().getBeansOfType(PlatformRequestAdmissionService.class).values()){
            Object rates=ReflectionTestUtils.getField(admission,"rates");if(rates instanceof Map<?,?> map)map.clear();
        }
        // Limits remain real within each test, including dedicated authentication throttling tests.
    }
}
