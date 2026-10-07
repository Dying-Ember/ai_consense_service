package com.consense.ai;
import com.fasterxml.jackson.annotation.*;
import lombok.Getter;

/** Configured adapter identity, never provider-attested execution or a credential fingerprint. */
@Getter
public final class ModelIdentity {
    private final String profileId,provider,model,configurationSha256,identityScope;
    @JsonCreator public ModelIdentity(@JsonProperty("profileId") String profileId,@JsonProperty("provider") String provider,
            @JsonProperty("model") String model,@JsonProperty("configurationSha256") String configurationSha256,
            @JsonProperty("identityScope") String identityScope) {
        this.profileId=profileId;this.provider=provider;this.model=model;this.configurationSha256=configurationSha256;this.identityScope=identityScope;
    }
}
