package com.consense.service.vetting;

import com.consense.config.ConsenseProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

class VettingH800BudgetProfileTest {
    @Test void realDeploymentProfileUsesCompetitionBudgetAtTheProviderCounterBoundary() {
        YamlPropertiesFactoryBean yaml=new YamlPropertiesFactoryBean();yaml.setResources(new ClassPathResource("application-vetting-h800.yml"));
        StandardEnvironment env=new StandardEnvironment();env.getPropertySources().addFirst(new PropertiesPropertySource("h800",yaml.getObject()));
        ConsenseProperties p=Binder.get(env).bind("consense",ConsenseProperties.class).get();
        assertFalse(p.getVetting().getResponses().isEnabled());
        p.getVetting().getResponses().setEnabled(true);p.getVetting().getResponses().setApiKey(VettingResponsesTransportTest.SECRET);
        VettingResponsesTransportTest.Http http=new VettingResponsesTransportTest.Http();
        VettingResponsesTransport transport=new VettingResponsesTransport(p,http);
        VettingInputBudget.Input input=VettingResponsesTransportTest.input(transport);
        http.count=98304;assertTrue(VettingInputBudget.check(input,transport::observe).isExtraDispatchPermitted());
        http.count=98305;assertEquals("over_budget",VettingInputBudget.check(input,transport::observe).getStatus());
        assertEquals(131072,input.getWire().getConfiguredContextTokens());assertEquals(16384,input.getWire().getSafetyMarginTokens());
        assertEquals(45000,p.getVetting().getResponses().getCounterTimeoutMs());
        assertTrue(http.endpoints.stream().allMatch(x->x.endsWith("/input_tokens")));
    }
}
