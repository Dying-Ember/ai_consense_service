package com.consense.service.advice;

import com.consense.config.ConsenseProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Advice-only route. Unknown/off policy never falls back from Responses to Chat. */
@Data
@Component
@ConfigurationProperties(prefix="consense.advice")
public class AdviceModelProperties {
    private String minimaxWire="chat";
    /** Advice-only exact HTTP entity recording; blank disables it. Existing owned absolute directory required. */
    private String wireObservationRoot;
    private int historyMaxExchanges=5;
    private int historyMaxMessages=10;
    private int historyMaxTokens=6000;
    private ConsenseProperties.Responses responses=new ConsenseProperties.Responses();
}
