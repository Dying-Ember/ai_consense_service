package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.domain.DraftVariable;
import java.util.*;
import java.util.regex.Pattern;

/** Input type validation is separate from applicability, source completeness and adoption. */
public final class DraftInputRules {
 private DraftInputRules() { }
 private static final Pattern FLOOR_LABEL=Pattern.compile("(?i)^\\s*floor\\s+([0-9]+(?:st|nd|rd|th)?)\\s*$");
 public static String normalizeSuggestion(DraftBlueprint.InputSpec spec,String value){
  return normalizeSuggestion(spec,value,null);
 }
 public static String normalizeSuggestion(DraftBlueprint.InputSpec spec,String value,String quote){
  return normalizeSuggestion(spec,value,quote,quote,quote);
 }
 public static String normalizeSuggestion(DraftBlueprint.InputSpec spec,String value,String quote,String supplied,String original){
  if(spec!=null)value=DraftArchitectContactEvidence.suggestion(spec.key,value,quote,supplied,original);
  if(spec!=null&&Arrays.asList("specificationInspectionBlock","drawingsInspectionBlock").contains(spec.key))value=blockIdentifier(value);
  if(spec!=null&&Arrays.asList("specificationInspectionFloor","drawingsInspectionFloor").contains(spec.key)&&value!=null){
   java.util.regex.Matcher floor=FLOOR_LABEL.matcher(value);if(floor.matches())value=floor.group(1);
  }
  if(spec!=null&&"sections".equals(spec.key)&&DraftBusinessRules.parse(value) instanceof List){
   List<Object> rows=new ArrayList<>();for(Object item:DraftBusinessRules.list(DraftBusinessRules.parse(value))){
    if(!(item instanceof Map)){rows.add(item);continue;}Map<String,Object> row=new LinkedHashMap<>(DraftBusinessRules.asMap(item));row.remove("id");rows.add(row);
   }value=JsonUtils.write(rows);
  }
  return normalize(spec,value);
 }
 static String blockIdentifier(String value){return value==null?null:value.replaceFirst("(?i)^\\s*Block\\s+","").trim();}
 public static String normalize(DraftBlueprint.InputSpec spec,String value){
  if(spec==null)throw new BizException(4007,"Unknown drafting input");if(value==null||value.trim().isEmpty())return "";
  if("text".equals(spec.kind)||"date".equals(spec.kind))return value;
  Object raw=DraftBusinessRules.parse(value);if(raw==null||"unknown".equals(raw))return "";
  if("boolean".equals(spec.kind)){Boolean b=DraftBusinessRules.truth(raw);if(b==null)throw invalid(spec);return String.valueOf(b);}
  if("contract".equals(spec.kind)){if(!(raw instanceof Map))throw invalid(spec);Map<String,Object>r=new LinkedHashMap<>(DraftBusinessRules.asMap(raw));for(String key:Arrays.asList("number","title"))if(!r.containsKey(key))r.put(key,"");return JsonUtils.write(r);}
  if("list".equals(spec.kind)||"multiselect".equals(spec.kind)){
   if(!(raw instanceof List))throw invalid(spec);List<Object>rows=new ArrayList<>(DraftBusinessRules.list(raw));
   if("multiselect".equals(spec.kind)){Set<String>seen=new HashSet<>();for(Object o:rows)if(!(o instanceof String)||!spec.options.contains(o)||!seen.add((String)o))throw invalid(spec);List<String>sorted=new ArrayList<>();for(String opt:spec.options)if(seen.contains(opt))sorted.add(opt);return JsonUtils.write(sorted);}
   if("billNos".equals(spec.key)){for(int i=0;i<rows.size();i++){Object o=rows.get(i);if(!(o instanceof Map))throw invalid(spec);Map<String,Object>r=new LinkedHashMap<>(DraftBusinessRules.asMap(o));if(!DraftBusinessRules.answered(r.get("id")))r.put("id","bill-"+UUID.nameUUIDFromBytes((String.valueOf(r.get("type"))+"|"+String.valueOf(r.get("number"))+"|"+String.valueOf(r.get("description"))).getBytes(java.nio.charset.StandardCharsets.UTF_8)));rows.set(i,r);}}
   return JsonUtils.write(rows);
  }
  if("number".equals(spec.kind)){if(raw instanceof Number)return JsonUtils.write(raw);try{Double.parseDouble(String.valueOf(raw));return String.valueOf(raw);}catch(RuntimeException ex){return value;}}
  if(!spec.options.isEmpty()&&!spec.options.contains(String.valueOf(raw)))throw invalid(spec);
  // Preserve exact user-entered free text, including whitespace and enum-looking strings.
  return raw instanceof String?(String)raw:value;
 }
 private static BizException invalid(DraftBlueprint.InputSpec spec){return new BizException(4007,"Invalid input type for "+spec.labelEn);}
 /** Invalid manual rows stay in storage; this catalog validation excludes them from generation. */
 static String structuredIssue(String key,Object value){
  DraftBlueprint.InputSpec spec=DraftBlueprint.find(key);
  if(spec==null||!"list".equals(spec.kind)||!DraftBusinessRules.answered(value))return null;
  if(!(value instanceof List))return "Enter a list of records; the entered value is retained for correction.";
  List<Object> columns=DraftBusinessRules.list(spec.schema.get("columnFields"));
  for(Object row:DraftBusinessRules.list(value)){
   Map<String,Object> record=DraftBusinessRules.asMap(row);
   // Historical one-column text lists remain compatible with the structured editor.
   if(row instanceof String&&columns.size()==1)record=DraftBusinessRules.map(DraftBusinessRules.asMap(columns.get(0)).get("key"),row);
   if(columns.isEmpty()){if(!(row instanceof String)||!DraftBusinessRules.answered(row))return "Complete every list entry; incomplete values remain stored for correction.";continue;}
   if(record.isEmpty())return "Complete every record using the required fields; entered rows remain stored for correction.";
   for(Object entry:columns){Map<String,Object> column=DraftBusinessRules.asMap(entry);String columnKey=String.valueOf(column.get("key"));Object cell=record.get(columnKey);
    if("id".equals(columnKey)||Boolean.FALSE.equals(DraftBusinessRules.condition(column.get("condition"),record)))continue;
    if(!DraftBusinessRules.answered(cell)){if(Boolean.TRUE.equals(column.get("optional")))continue;return "Complete the required "+columnKey+" field in every record; entered rows remain stored for correction.";}
    if(!validCell(column,cell))return "Use a valid "+columnKey+" value in every record; entered rows remain stored for correction.";
   }
  }
  return null;
 }
 private static boolean validCell(Map<String,Object> column,Object cell){
  String kind=String.valueOf(column.get("kind"));List<Object> options=DraftBusinessRules.list(column.get("options"));
  if("multiselect".equals(kind)){if(!(cell instanceof List))return false;Set<Object> seen=new HashSet<>();for(Object choice:DraftBusinessRules.list(cell))if(!seen.add(choice)||!validOption(options,choice))return false;return true;}
  if("boolean".equals(kind))return DraftBusinessRules.truth(cell)!=null;
  if(!options.isEmpty())return validOption(options,cell);
  if("number".equals(kind))return DraftBusinessRules.number(cell)!=null&&DraftBusinessRules.number(cell)>=0;
  if("date".equals(kind)){try{java.time.LocalDate.parse(String.valueOf(cell));return true;}catch(RuntimeException invalid){return false;}}
  return cell instanceof String;
 }
 private static boolean validOption(List<Object> options,Object value){if(options.isEmpty())return DraftBusinessRules.answered(value);for(Object option:options)if(Objects.equals(String.valueOf(DraftBusinessRules.asMap(option).get("value")),String.valueOf(value)))return true;return false;}
 public static boolean valid(DraftVariable variable){DraftBlueprint.InputSpec spec=DraftBlueprint.find(variable.getVarKey());if(spec==null||variable.getValueText()==null||variable.getValueText().trim().isEmpty())return false;try{Object value=DraftBusinessRules.parse(variable.getValueText());if(!DraftBusinessRules.answered(value)||DraftBusinessRules.invalid(spec.key,value)!=null)return false;if("boolean".equals(spec.kind))return DraftBusinessRules.truth(value)!=null;if("contract".equals(spec.kind)){Map<String,Object>r=DraftBusinessRules.asMap(value);return DraftBusinessRules.answered(r.get("number"))&&DraftBusinessRules.answered(r.get("title"));}return !normalize(spec,variable.getValueText()).isEmpty();}catch(RuntimeException ex){return false;}}
 /** Drafts may contain unresolved items. No all-inputs-confirmed approval gate. */
 public static void requireReady(List<DraftVariable> variables) { }
 public static Map<String,Object> derived(List<DraftVariable> variables,String template){Map<String,Object>p=DraftBusinessRules.plan(DraftBusinessRules.decode(variables));Map<String,Object>derived=new LinkedHashMap<>(DraftBusinessRules.asMap(p.get("derived")));derived.put("plan",p);derived.put("ntt13InstructionPresent",hasNscInstruction(template==null?"":template));derived.put("bondRule","Choose G1a only when both combined foundation and >=39 month threshold are true; unknown is not false.");derived.put("nscRule","Use exact adopted NTT13 target wording for BSSSC; do not delete the requirement or guess the applicable NSC SCC template.");derived.put("tenderMethodRule","BQ issue format is independent of pricing-document type; retain specified printed and DVD return requirements.");return derived;}
 public static boolean hasNscInstruction(String text){return Pattern.compile("(?i)\\*\\s*Amend\\s+if\\s+NSC\\s+is\\s+not\\s+applicable").matcher(text).find();}
}
