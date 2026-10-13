package com.consense.service.sources;

import com.fasterxml.jackson.core.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.*;
import java.util.*;

/** Sort object keys without changing Python-produced number tokens or unknown fields. */
final class CanonicalJson {
    private CanonicalJson(){}
    private static final JsonFactory FACTORY=new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    static String hash(String json,String omitRootField) {
        try(JsonParser parser=FACTORY.createParser(json)) {
            parser.nextToken();String value=read(parser,omitRootField);
            if(parser.nextToken()!=null)throw new IOException("Trailing JSON");
            return sha(value.getBytes(StandardCharsets.UTF_8));
        }catch(IOException|IllegalArgumentException error){throw new CanonicalEvidenceException(org.springframework.http.HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}
    }
    static String fieldHash(String json,String name) {
        try(JsonParser p=FACTORY.createParser(json)){
            if(p.nextToken()!=JsonToken.START_OBJECT)throw new IOException("Expected object");
            String found=null;
            while(p.nextToken()!=JsonToken.END_OBJECT){String key=p.getCurrentName();p.nextToken();String value=read(p,null);if(key.equals(name))found=value;}
            if(found==null||p.nextToken()!=null)throw new IOException("Missing field or trailing JSON");
            return sha(found.getBytes(StandardCharsets.UTF_8));
        }catch(IOException|IllegalArgumentException error){throw new CanonicalEvidenceException(org.springframework.http.HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}
    }
    static String sha(byte[] bytes) {
        try {StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format("%02x",b&255));return out.toString();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    /** Strict Python-produced wire only. Existing hash/read behavior above remains unchanged. */
    static String strictCanonical(String json) {
        try(JsonParser parser=FACTORY.createParser(json)) {
            parser.nextToken();String value=strictRead(parser);
            if(parser.nextToken()!=null)throw new IOException("Trailing JSON");
            return value;
        }catch(IOException|IllegalArgumentException error){throw new CanonicalEvidenceException(org.springframework.http.HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}
    }
    static void requireCanonical(String raw) {
        if(!strictCanonical(raw).equals(raw))throw new CanonicalEvidenceException(org.springframework.http.HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_NONCANONICAL");
    }
    static Map<String,String> rawFields(String json) {
        requireCanonical(json);
        try(JsonParser p=FACTORY.createParser(json)) {
            if(p.nextToken()!=JsonToken.START_OBJECT)throw new IOException("Expected object");
            Map<String,String> out=new TreeMap<>(CanonicalJson::compareCodepoints);
            while(p.nextToken()!=JsonToken.END_OBJECT){String name=p.getCurrentName();p.nextToken();out.put(name,read(p,null));}
            if(p.nextToken()!=null)throw new IOException("Trailing JSON");return out;
        }catch(IOException error){throw new CanonicalEvidenceException(org.springframework.http.HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}
    }
    static List<String> rawElements(String json) {
        requireCanonical(json);
        try(JsonParser p=FACTORY.createParser(json)) {
            if(p.nextToken()!=JsonToken.START_ARRAY)throw new IOException("Expected array");
            List<String> out=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY)out.add(read(p,null));
            if(p.nextToken()!=null)throw new IOException("Trailing JSON");return out;
        }catch(IOException error){throw new CanonicalEvidenceException(org.springframework.http.HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}
    }
    static String rawObject(Map<String,String> fields) {
        Map<String,String> sorted=new TreeMap<>(CanonicalJson::compareCodepoints);sorted.putAll(fields);
        List<String> pairs=new ArrayList<>();for(Map.Entry<String,String> row:sorted.entrySet())pairs.add(quote(row.getKey())+":"+row.getValue());
        return "{"+String.join(",",pairs)+"}";
    }
    static String rawArray(List<String> values){return "["+String.join(",",values)+"]";}
    static String scalar(String value){return quote(value);}
    private static String strictRead(JsonParser p)throws IOException {
        JsonToken token=p.currentToken();
        if(token==JsonToken.START_OBJECT){Map<String,String> out=new TreeMap<>(CanonicalJson::compareCodepoints);
            while(p.nextToken()!=JsonToken.END_OBJECT){String name=p.getCurrentName();p.nextToken();out.put(name,strictRead(p));}return rawObject(out);}
        if(token==JsonToken.START_ARRAY){List<String> out=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY)out.add(strictRead(p));return rawArray(out);}
        if(token==JsonToken.VALUE_NUMBER_INT){String raw=p.getText();if(!new BigInteger(raw).toString().equals(raw))throw new IOException("Noncanonical integer");return raw;}
        if(token==JsonToken.VALUE_NUMBER_FLOAT){String raw=p.getText();if(!pythonFloat(raw).equals(raw))throw new IOException("Noncanonical float");return raw;}
        if(token==JsonToken.VALUE_STRING)return quote(p.getText());
        if(token==JsonToken.VALUE_TRUE)return "true";if(token==JsonToken.VALUE_FALSE)return "false";if(token==JsonToken.VALUE_NULL)return "null";
        throw new IOException("Invalid JSON token");
    }
    /** Shortest nearest decimal that roundtrips to the binary64 value, with Python repr formatting. */
    private static String pythonFloat(String raw)throws IOException {
        double value=Double.parseDouble(raw);if(!Double.isFinite(value))throw new IOException("Nonfinite float");
        if(value==0.0)return Double.doubleToRawLongBits(value)<0?"-0.0":"0.0";
        BigDecimal exact=new BigDecimal(Math.abs(value)),shortest=null;
        long bits=Double.doubleToRawLongBits(Math.abs(value));
        int decimalExponent=exact.precision()-exact.scale()-1;
        for(int digits=1;digits<=17;digits++){
            BigDecimal nearest=exact.round(new MathContext(digits,RoundingMode.HALF_EVEN));
            BigDecimal step=BigDecimal.ONE.scaleByPowerOfTen(decimalExponent-digits+1),best=null,bestDistance=null;
            // Binary64 intervals are asymmetric at powers of two. The nearest
            // decimal may miss that interval while an adjacent decimal fits.
            for(int neighbor=-1;neighbor<=1;neighbor++){
                BigDecimal candidate=nearest.add(step.multiply(BigDecimal.valueOf(neighbor))).stripTrailingZeros();
                if(candidate.signum()<=0||candidate.precision()>digits
                        ||Double.doubleToRawLongBits(Double.parseDouble(candidate.toString()))!=bits)continue;
                BigDecimal distance=exact.subtract(candidate).abs();
                int comparison=bestDistance==null?-1:distance.compareTo(bestDistance);
                if(comparison<0||(comparison==0&&!candidate.unscaledValue().testBit(0))){best=candidate;bestDistance=distance;}
            }
            if(best!=null){shortest=best;break;}
        }
        if(shortest==null)throw new IOException("Float roundtrip unavailable");
        String digits=shortest.unscaledValue().abs().toString();int exponent=digits.length()-shortest.scale()-1;
        String sign=value<0?"-":"";
        if(exponent< -4||exponent>=16){String mantissa=digits.substring(0,1)+(digits.length()>1?"."+digits.substring(1):"");
            return sign+mantissa+"e"+(exponent<0?"-":"+")+String.format(java.util.Locale.ROOT,"%02d",Math.abs(exponent));}
        String plain=shortest.toPlainString();return sign+plain+(plain.contains(".")?"":".0");
    }
    private static String read(JsonParser p,String omit) throws IOException {
        JsonToken token=p.currentToken();
        if(token==JsonToken.START_OBJECT){
            Map<String,String> fields=new TreeMap<>(CanonicalJson::compareCodepoints);
            while(p.nextToken()!=JsonToken.END_OBJECT){String key=p.getCurrentName();p.nextToken();String value=read(p,null);if(!key.equals(omit))fields.put(key,value);}
            List<String> pairs=new ArrayList<>();for(Map.Entry<String,String> e:fields.entrySet())pairs.add(quote(e.getKey())+":"+e.getValue());return "{"+String.join(",",pairs)+"}";
        }
        if(token==JsonToken.START_ARRAY){List<String> values=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY)values.add(read(p,null));return "["+String.join(",",values)+"]";}
        if(token==JsonToken.VALUE_STRING)return quote(p.getText());
        if(token==JsonToken.VALUE_NUMBER_INT||token==JsonToken.VALUE_NUMBER_FLOAT)return p.getText();
        if(token==JsonToken.VALUE_TRUE)return "true";if(token==JsonToken.VALUE_FALSE)return "false";if(token==JsonToken.VALUE_NULL)return "null";
        throw new IOException("Invalid JSON token");
    }
    private static int compareCodepoints(String a,String b){int x=0,y=0;while(x<a.length()&&y<b.length()){int ca=a.codePointAt(x),cb=b.codePointAt(y);if(ca!=cb)return Integer.compare(ca,cb);x+=Character.charCount(ca);y+=Character.charCount(cb);}return Integer.compare(a.length()-x,b.length()-y);}
    private static String quote(String value){StringBuilder b=new StringBuilder("\"");for(int i=0;i<value.length();i++){char c=value.charAt(i);switch(c){case '"':b.append("\\\"");break;case '\\':b.append("\\\\");break;case '\b':b.append("\\b");break;case '\f':b.append("\\f");break;case '\n':b.append("\\n");break;case '\r':b.append("\\r");break;case '\t':b.append("\\t");break;default:if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}return b.append('"').toString();}
}
