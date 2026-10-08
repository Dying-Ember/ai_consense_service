package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Optional vetting-only CPU counter. A sealed recipe is a trusted deployment declaration, not live model health. */
@Component
public class VettingTokenBudgetCliObserver implements VettingInputBudget.Observer {
    public static final String PROTOCOL="vetting-bound-token-budget-cli-recipe-v1";
    private final ConsenseProperties properties;
    public VettingTokenBudgetCliObserver(ConsenseProperties properties){this.properties=properties;}

    @Override public VettingInputBudget.Observation observe(VettingInputBudget.Input input) {
        ConsenseProperties.Vetting cfg=properties.getVetting();
        if(!cfg.isTokenBudgetObserverEnabled())return null;
        Process process=null;ExecutorService workers=null;
        try {
            if(cfg.getTokenBudgetObserverTimeoutMs()<=0||cfg.getTokenBudgetObserverTimeoutMs()>60000
                    ||cfg.getTokenBudgetObserverMaxOutputBytes()<1024||cfg.getTokenBudgetObserverMaxOutputBytes()>4194304)throw new IllegalArgumentException("Counter limits invalid");
            Path recipePath=Paths.get(cfg.getTokenBudgetObserverRecipePath()).toAbsolutePath().normalize();
            requireSha(recipePath,cfg.getTokenBudgetObserverRecipeSha256(),null);
            JsonNode recipe=strictJson(Files.readAllBytes(recipePath));validateRecipe(recipe,input,cfg);
            List<String> command=new ArrayList<>(cfg.getTokenBudgetObserverCommand());
            workers=Executors.newFixedThreadPool(3,r->{Thread t=new Thread(r,"vetting-token-counter-io");t.setDaemon(true);return t;});
            ProcessBuilder builder=new ProcessBuilder(command);builder.environment().clear();
            process=builder.start();final Process child=process;final int cap=cfg.getTokenBudgetObserverMaxOutputBytes();
            Future<byte[]> stdout=workers.submit(()->readLimited(child.getInputStream(),cap));
            Future<byte[]> stderr=workers.submit(()->readLimited(child.getErrorStream(),cap));
            byte[] exactInput=JsonUtils.write(input).getBytes(StandardCharsets.UTF_8);
            Future<?> stdin=workers.submit(()->{try(OutputStream out=child.getOutputStream()){out.write(exactInput);out.flush();}return null;});
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(cfg.getTokenBudgetObserverTimeoutMs());
            if(!process.waitFor(cfg.getTokenBudgetObserverTimeoutMs(),TimeUnit.MILLISECONDS))throw new IOException("Counter timed out");
            stdin.get(remaining(deadline),TimeUnit.NANOSECONDS);
            byte[] response=stdout.get(remaining(deadline),TimeUnit.NANOSECONDS);stderr.get(remaining(deadline),TimeUnit.NANOSECONDS);
            if(process.exitValue()!=0)throw new IOException("Counter exited unsuccessfully");
            requireSha(recipePath,cfg.getTokenBudgetObserverRecipeSha256(),null);validateRecipe(recipe,input,cfg);
            JsonNode raw=strictJson(response);validateObservation(raw,recipe,input);
            return JsonUtils.read(JsonUtils.write(raw),VettingInputBudget.Observation.class);
        } catch(Exception failure) {
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            // Do not expose stderr, input, command, endpoint, recipe paths or inherited credentials.
            throw new IllegalStateException("Bound token counter failed: "+failure.getClass().getSimpleName());
        } finally {
            if(process!=null){process.destroyForcibly();close(process.getInputStream());close(process.getErrorStream());close(process.getOutputStream());}
            if(workers!=null)workers.shutdownNow();
        }
    }
    private static long remaining(long deadline)throws IOException {long left=deadline-System.nanoTime();if(left<=0)throw new IOException("Counter I/O timed out");return left;}
    private static void close(Closeable stream){try{stream.close();}catch(IOException ignored){}}
    private static byte[] readLimited(InputStream in,int cap)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1){if(n>cap-out.size())throw new IOException("Counter output exceeds bound");out.write(block,0,n);}return out.toByteArray();}
    private static JsonNode strictJson(byte[] bytes)throws IOException {
        try(JsonParser parser=JsonUtils.mapper().getFactory().createParser(bytes)){parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);JsonNode n=JsonUtils.mapper().readTree(parser);if(n==null||!n.isObject()||parser.nextToken()!=null)throw new IOException("Counter JSON must be one object");return n;}
    }
    private static String text(JsonNode node,String key){JsonNode n=node.get(key);if(n==null||!n.isTextual())throw new IllegalArgumentException("Missing recipe identity");return n.textValue();}
    private static long positive(JsonNode node,String key){JsonNode n=node.get(key);if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<=0)throw new IllegalArgumentException("Invalid token count");return n.longValue();}
    private static void match(JsonNode node,String key,String value){if(value==null||!value.equals(text(node,key)))throw new IllegalArgumentException("Counter identity differs");}
    private static void validateRecipe(JsonNode r,VettingInputBudget.Input input,ConsenseProperties.Vetting cfg)throws Exception {
        match(r,"protocol",PROTOCOL);match(r,"contextEvidenceScope","current_deployment_configuration_bound");
        match(r,"provider",input.getProvider());match(r,"model",input.getModel());match(r,"providerConfigurationSha256",input.getProviderConfigurationSha256());
        if(input.getProviderConfigurationSha256()==null||!input.getProviderConfigurationSha256().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Unknown provider configuration");
        for(String k:Arrays.asList("tokenizerIdentitySha256","chatTemplateIdentitySha256","effectiveContextIdentitySha256"))if(!text(r,k).matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid recipe hash");
        if(positive(r,"outputReserveTokens")!=input.getOutputReserveTokens())throw new IllegalArgumentException("Output reserve differs");positive(r,"effectiveContextTokens");
        JsonNode command=r.get("command");if(command==null||!command.isArray()||command.size()==0)throw new IllegalArgumentException("Counter command missing");
        List<String> sealed=new ArrayList<>();for(JsonNode arg:command){if(!arg.isTextual()||arg.textValue().isEmpty())throw new IllegalArgumentException("Counter command invalid");sealed.add(arg.textValue());}
        if(!sealed.equals(cfg.getTokenBudgetObserverCommand())||!Paths.get(sealed.get(0)).isAbsolute())throw new IllegalArgumentException("Counter command differs");
        JsonNode artifacts=r.get("softwareArtifacts");if(artifacts==null||!artifacts.isArray()||artifacts.size()==0)throw new IllegalArgumentException("Counter artifacts missing");
        Set<Path> seen=new HashSet<>();Path executable=Paths.get(sealed.get(0)).toAbsolutePath().normalize();
        for(JsonNode a:artifacts){Path p=Paths.get(text(a,"path")).toAbsolutePath().normalize();if(!seen.add(p))throw new IllegalArgumentException("Duplicate counter artifact");requireSha(p,text(a,"sha256"),positive(a,"bytes"));}
        if(!seen.contains(executable))throw new IllegalArgumentException("Executable not sealed");
    }
    private static void validateObservation(JsonNode o,JsonNode recipe,VettingInputBudget.Input input) {
        Set<String> fields=new HashSet<>(Arrays.asList("model","serializedInputSha256","tokenizerIdentitySha256","chatTemplateIdentitySha256","effectiveContextIdentitySha256","inputTokens","effectiveContextTokens","outputReserveTokens","completeChatTemplateAndSchemaObserved","scope"));
        Iterator<String> keys=o.fieldNames();while(keys.hasNext())if(!fields.contains(keys.next()))throw new IllegalArgumentException("Unexpected observation field");
        match(o,"model",input.getModel());match(o,"serializedInputSha256",input.getSerializedInputSha256());match(o,"scope","complete_serialized_messages_with_gateway_schema_envelope");
        for(String k:Arrays.asList("tokenizerIdentitySha256","chatTemplateIdentitySha256","effectiveContextIdentitySha256"))match(o,k,text(recipe,k));
        positive(o,"inputTokens");if(positive(o,"effectiveContextTokens")!=positive(recipe,"effectiveContextTokens")||positive(o,"outputReserveTokens")!=input.getOutputReserveTokens())throw new IllegalArgumentException("Observed budget differs");
        JsonNode complete=o.get("completeChatTemplateAndSchemaObserved");if(complete==null||!complete.isBoolean()||!complete.booleanValue())throw new IllegalArgumentException("Incomplete chat observation");
    }
    private static void requireSha(Path p,String expected,Long size)throws Exception {
        if(expected==null||!expected.matches("[a-f0-9]{64}")||!Files.isRegularFile(p)||size!=null&&Files.size(p)!=size)throw new IllegalArgumentException("Counter artifact identity invalid");
        MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(p)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);}
        StringBuilder actual=new StringBuilder();for(byte b:hash.digest())actual.append(String.format("%02x",b&255));if(!actual.toString().equals(expected))throw new IllegalArgumentException("Counter artifact changed");
    }
}
