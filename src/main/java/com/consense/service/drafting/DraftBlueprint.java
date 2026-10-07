package com.consense.service.drafting;
import java.util.*;
/** The versioned server catalog is the question source. Historical keys stay same-source. */
public final class DraftBlueprint {
 private DraftBlueprint() { }
 public static final List<String> TEMPLATE_KEYS=Collections.unmodifiableList(Arrays.asList("NTT","SCT","SCC"));
 public static List<String> draftFileKeys() { return TEMPLATE_KEYS; }
 public static String fileTitle(String key) { return "NTT".equals(key)?"Notes to Tenderers":"SCT".equals(key)?"Special Conditions of Tender":"SCC".equals(key)?"Special Conditions of Contract":key==null?"":key; }
 public static final class InputSpec {
  public final String key,group,labelZhHans,labelZhHant,labelEn,action,kind,affects;
  public final List<String> options; public final boolean optional,hidden; public final Map<String,Object> schema;
  @SuppressWarnings("unchecked") InputSpec(Map<String,Object> f,String group) {
   this.schema=Collections.unmodifiableMap(f);this.key=String.valueOf(f.get("key"));this.group=group;
   Map<String,Object> label=(Map<String,Object>)f.get("label");labelZhHans=String.valueOf(label.get("zhHans"));labelZhHant=String.valueOf(label.get("zhHant"));labelEn=String.valueOf(label.get("en"));kind=String.valueOf(f.get("kind"));
   optional=Boolean.TRUE.equals(f.get("optional"));hidden=Boolean.TRUE.equals(f.get("hidden"));
   List<String> opts=new ArrayList<>(); for(Object o:(List<Object>)f.getOrDefault("options",Collections.emptyList())) opts.add(String.valueOf(((Map<String,Object>)o).get("value")));options=Collections.unmodifiableList(opts);action=opts.isEmpty()?"fill":"choice";
   Set<String> docs=new LinkedHashSet<>(); for(Object o:(List<Object>)f.getOrDefault("affects",Collections.emptyList())) docs.add(String.valueOf(((Map<String,Object>)o).get("document")));affects=String.join(",",docs);
  }
 }
 public static final List<String> SERVICES=Collections.unmodifiableList(Arrays.asList("Electrical","Fire services","Fire services and water pump","Air-conditioning and mechanical ventilation","Lift","Lift and escalator"));
 public static final List<InputSpec> INPUTS=loadInputs();
 @SuppressWarnings("unchecked") private static List<InputSpec> loadInputs() {
  List<InputSpec> out=new ArrayList<>();Map<String,Object> catalog=DraftBusinessRules.catalog();
  for(Object o:(List<Object>)catalog.get("groups")) {Map<String,Object> group=(Map<String,Object>)o;for(Object f:(List<Object>)group.get("fields")) out.add(new InputSpec((Map<String,Object>)f,String.valueOf(group.get("id"))));}
  for(Object f:(List<Object>)catalog.getOrDefault("systemFields",Collections.emptyList())) out.add(new InputSpec((Map<String,Object>)f,"system"));return Collections.unmodifiableList(out);
 }
 public static InputSpec find(String key) { if("targetEdits".equals(key)) key="targetOverrides";for(InputSpec s:INPUTS) if(s.key.equalsIgnoreCase(key==null?"":key)) return s;return null; }
 public static boolean isInput(String key) { return find(key)!=null; }
 public static String keysAsString() {StringBuilder out=new StringBuilder();for(InputSpec s:INPUTS) if(!s.hidden) out.append(s.key).append(" — ").append(s.labelEn).append(" type=").append(s.kind).append(s.options.isEmpty()?"":" options="+s.options).append("\n");return out.toString();}
}
