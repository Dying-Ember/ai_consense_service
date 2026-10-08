package com.consense.service.drafting;

import java.util.*;

/** One admissibility policy for saved target decisions and rule planning. */
final class DraftTargetDecisions {
    private DraftTargetDecisions() { }
    private static final Set<String> ACTIONS=Collections.unmodifiableSet(new HashSet<>(Arrays.asList("retain","amend","not_used","delete","not_adopted")));
    static Object text(Map<String,Object> edit){return edit.containsKey("adoptedText")?edit.get("adoptedText"):edit.get("value");}
    private static boolean exactText(Object value){return value instanceof String&&DraftBusinessRules.answered(value);}
    static String problem(Map<String,Object> target,Map<String,Object> edit){
        String id=String.valueOf(edit.get("actionId")),action=String.valueOf(edit.get("action"));
        if(target==null)return "Unknown target action ID: "+id;
        if(!ACTIONS.contains(action))return "Invalid target action: "+action;
        boolean hasText=exactText(text(edit));
        if("amend".equals(action)&&!hasText)return "An amendment needs the exact adopted English wording.";
        boolean sourceCheck="SourceCheck".equals(target.get("pendingKind"))||id.contains("REFERENCE")||id.contains("reference-check");
        if(sourceCheck&&!hasText&&!exactText(edit.get("sourceMapping")))return "This target requires sourced edition mapping or exact adopted wording.";
        if(edit.containsKey("document")&&!Objects.equals(String.valueOf(target.get("document")),String.valueOf(edit.get("document"))))return "Target document does not match the catalogue.";
        if(edit.containsKey("clause")&&!Objects.equals(String.valueOf(target.get("clause")),String.valueOf(edit.get("clause"))))return "Target clause does not match the catalogue.";
        return null;
    }
}
