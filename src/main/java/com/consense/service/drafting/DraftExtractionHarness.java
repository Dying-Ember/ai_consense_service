package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.service.prompt.PromptService;
import com.consense.web.dto.DraftingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Drafting-local request/intake boundary. Accepted intake never certifies business truth. */
@Service @RequiredArgsConstructor
public class DraftExtractionHarness {
    public static final String VERSION="draft-extraction-20261008.20-formal-bill-identities";
    private static final String PROTOCOL="DRAFTING EXTRACTION PROTOCOL. Return only a JSON array of supported items {key,value,sourceQuote,reason,confidence}. "
            +"Use native JSON values: object, array, boolean, number, string or null, according to the single catalogue below. "
            +"Example contract value: {\"number\":\"C-2026/10\",\"title\":\"Works A\"}. A supported partial contract may omit its missing sibling. "
            +"Example Bill value: [{\"number\":\"1\",\"description\":\"Exact formal description\"}]. Never invent missing identifiers or descriptions. "
            +"Requests asking for missing information, questions and explicitly pending classifications are not answers. Actual project instructions or decisions can support editable suggestions; later UI adoption is separate. "
            +"A reference to another source does not supply that source's missing details. Return no guessed values or empty copies of the catalogue. "
            +"If the source expressly leaves an input pending, not supplied or blank, return that key with value null, its explanation and one exact contiguous unresolved-source quote. Only inputs unmentioned in this part may be omitted. false is an explicit No and [] an explicitly justified empty list. "
            +"Collections have three states: source-confirmed absence is []; pending, missing information or insufficient facts are null; explicitly provided applicable items are a concrete list. [] requires an exact quote and complete original context establishing absence for this field and scope. A failed search is not absence. "
            +"Examples: 'No additional tender requirements apply to this project.' -> value []; 'Additional tender requirements remain pending.' or 'No requirement can be established from the available facts.' -> value null; 'Provide a signed plan with the tender.' -> value [{\"text\":\"Provide a signed plan with the tender.\"}]. "
            +"Only text inside correspondence-part establishes values; templates, filenames, metadata and catalogue labels are not evidence. "
            +"Quotes must be contiguous source passages, copied exactly including punctuation. Use a short passage for scalar values; for a collection or numbered list, quote the complete defining list/table and its governing heading. Return every supplied applicable item in original order and retain its numbering and wording, including the final catch-all item. Never return only the prefix or tail of one complete source list. Confidence must be between 0 and 1. "
            +"Preserve formal English names. Type/purpose classifications are separate from literal Bill descriptions. "
            +"Bill type, purpose, trade and issue placement are optional: omit unknown cells. Each classification requires explicit evidence for that exact row; a schedule heading or the name Preliminaries does not classify every Bill. "
            +"L10Pro pricing preparation is independent of paper/DVD return. NSC/BSSSC arrangement is independent of trade scope. "
            +"otherTenderingArrangement is only an explicitly adopted alternative to the two-envelope tender procedure or replacement SCT5 wording. Hardcopy plus DVD-ROM submission under the existing two-envelope procedure is return media, not an alternative tender procedure; do not map it to this input. "
            +"A selected-trades quote must explicitly cover every selected trade. Cite a contiguous selected-trade table or the sentence defining the list, not only the NSC/BSSSC arrangement. Do not select No/excluded rows or duplicate combined Fire services and water pump or Lift and escalator with their shorter options. "
            +"Combined-foundation classification and >=39-month threshold are separate inputs; a baseline/ceiling does not establish the exact accepted period. "
            +"Derived results and targetOverrides are excluded. Suggestions are never automatic adoption.\n";
    private final AiGateway ai;

    public String systemPrompt(String configured) {
        // A custom legacy placeholder remains usable; the untouched default has no duplicated catalogue.
        String custom=configured!=null&&configured.contains("%s")?PromptService.format(configured,"See canonical editable catalogue below."):configured;
        return (custom==null?"":custom)+"\n"+PROTOCOL+"Canonical editable catalogue:\n"+catalogue(null);
    }

    public String userPrompt(String configured,ExtractionPartVO part) {
        String text=part.getContext()==null?part.getSourceText():part.getContext().getSourceText();
        String source="Source document: "+part.getFileName()+"; part "+part.getPartIndex()+"\n<correspondence-part>\n"+text+"\n</correspondence-part>";
        String customized=PromptService.format(configured,"Only supplied correspondence may establish project values.","Read the complete correspondence part below.");
        return customized+"\n"+source+(part.getContext()!=null&&"context_insufficient".equals(part.getContext().getStopReason())?
                "\nThe source logical unit exceeds this bounded window. Do not treat a partial table or paragraph as a complete adopted list or replacement text; unresolved completeness must remain explicit.":"");
    }

    private String catalogue(Set<String> allowed) {
        List<Map<String,Object>> fields=new ArrayList<>();
        for(DraftBlueprint.InputSpec spec:DraftBlueprint.INPUTS)if(!spec.hidden&&(allowed==null||allowed.contains(spec.key))) {
            Map<String,Object> field=new LinkedHashMap<>();field.put("key",spec.key);field.put("meaning",spec.labelEn);field.put("kind",spec.kind);
            if(!spec.options.isEmpty())field.put("options",spec.options);
            if(spec.schema.containsKey("columnFields")) {
                List<Map<String,Object>> columns=new ArrayList<>();
                for(Object item:(List<?>)spec.schema.get("columnFields")) {
                    Map<?,?> source=(Map<?,?>)item;Map<String,Object> column=new LinkedHashMap<>();
                    column.put("key",source.get("key"));column.put("kind",source.get("kind"));column.put("optional","contract".equals(spec.kind)||Boolean.TRUE.equals(source.get("optional")));
                    if(source.containsKey("condition"))column.put("condition",source.get("condition"));
                    if(source.containsKey("options")) {
                        List<Object> options=new ArrayList<>();for(Object option:(List<?>)source.get("options"))options.add(((Map<?,?>)option).get("value"));column.put("options",options);
                    }
                    columns.add(column);
                }
                field.put("columnFields",columns);
            }
            fields.add(field);
        }
        return JsonUtils.write(fields);
    }

    public void extractPart(ExtractTraceVO trace,ExtractionPartVO part,String system,String user) {
        extractPart(trace,part,system,user,part.getSourceText());
    }

    public void extractPart(ExtractTraceVO trace,ExtractionPartVO part,String system,String user,String originalSource) {
        int before=trace.getDecisions().size();
        for(int dispatch=1;dispatch<=2;dispatch++) {
            String effectiveUser=dispatch==1?user:user+"\nThe preceding response was not a decodable JSON array. Return only one JSON array.";
            ExtractionAttemptVO attempt=dispatch(trace,part,"primary",system,effectiveUser);
            JsonNode items;
            try{items=decode(attempt.getRawResponse());}
            catch(RuntimeException invalid) {
                attempt.setStatus("failed");attempt.setErrorCode("invalid_json");
                if(dispatch==2)throw new BizException(5002,"Model output is not a decodable JSON array; the failed attempts are retained in the extraction report.");
                continue;
            }
            attempt.setStatus("completed");int index=0;
            for(JsonNode item:items)trace.getDecisions().add(decide(part,attempt,index++,item,originalSource));
            break;
        }
        Set<String> accepted=new HashSet<>(),unansweredQuoteRepairs=new LinkedHashSet<>();Map<String,List<String>> failed=new LinkedHashMap<>();
        for(ExtractionDecisionVO decision:trace.getDecisions().subList(before,trace.getDecisions().size())) {
            if("accepted".equals(decision.getStatus()))accepted.add(decision.getKey());
            if("unanswered".equals(decision.getStatus())&&decision.getCodes().contains("quote_not_in_part")) {
                unansweredQuoteRepairs.add(decision.getKey());failed.computeIfAbsent(decision.getKey(),key->new ArrayList<>()).add("quote_not_in_part");continue;
            }
            if(!"rejected".equals(decision.getStatus())||DraftBlueprint.find(decision.getKey())==null)continue;
            // An unsupported absence needs original context, not a repair of the same empty answer.
            if(decision.getCodes().contains("unsupported_empty_list"))continue;
            if(decision.getCodes().contains("confidence_missing")||decision.getCodes().contains("confidence_out_of_range")||decision.getCodes().contains("confidence_below_threshold"))continue;
            if(!Collections.disjoint(decision.getCodes(),Arrays.asList("invalid_value_shape","quote_missing","quote_not_in_part","lexical_support_missing","quote_value_mismatch","source_list_incomplete","source_list_value_mismatch")))
                failed.computeIfAbsent(decision.getKey(),key->new ArrayList<>()).addAll(decision.getCodes());
        }
        accepted.forEach(failed::remove);
        accepted.forEach(unansweredQuoteRepairs::remove);
        if(!failed.isEmpty()) {
        String repairSystem=system.substring(0,system.lastIndexOf("Canonical editable catalogue:\n"))+"Canonical editable catalogue:\n"+catalogue(failed.keySet());
        String repairUser=user+"\nOne targeted repair only. Allowed keys: "+JsonUtils.write(failed.keySet())+"\nOriginal rejection reasons: "+JsonUtils.write(failed)
                +"\nReturn only corrected supported values for these rejected keys. Omit a key if the original source cannot support it. Do not replace any accepted key."
                +"\nUnknown-value citation repair keys: "+JsonUtils.write(unansweredQuoteRepairs)+". For these keys keep value null and repair only its exact contiguous quote and unresolved explanation; never fill a value such as a baseline duration.";
        ExtractionAttemptVO repair;
        try{repair=dispatch(trace,part,"repair",repairSystem,repairUser);}
        catch(RuntimeException modelFailure){if(ai.explicitProfileSelected())throw modelFailure;return;}
        try {
            JsonNode items=decode(repair.getRawResponse());repair.setStatus("completed");int index=0;
            for(JsonNode item:items) {
                ExtractionDecisionVO decision=decide(part,repair,index++,item,originalSource);
                if(!failed.containsKey(decision.getKey())) {decision.setStatus("rejected");decision.getCodes().add(0,"repair_key_not_allowed");}
                else if(unansweredQuoteRepairs.contains(decision.getKey())&&!"unanswered".equals(decision.getStatus())) {
                    decision.setStatus("rejected");decision.getCodes().add(0,"unanswered_repair_value_not_allowed");
                }
                trace.getDecisions().add(decision);
            }
        } catch(RuntimeException invalid) {repair.setStatus("failed");repair.setErrorCode("invalid_json");}
        }
        boolean domesticAssessed=trace.getDecisions().subList(before,trace.getDecisions().size()).stream()
                .anyMatch(decision->"domesticBlocks".equals(decision.getKey())&&
                        (!"unanswered".equals(decision.getStatus())&&!decision.getCodes().contains("unanswered_repair_value_not_allowed")||decision.getCodes().contains("source_unresolved")));
        if(!domesticAssessed&&domesticScope(part.getContext()==null?part.getSourceText():part.getContext().getSourceText())!=null) {
            String coverageSystem=system.substring(0,system.lastIndexOf("Canonical editable catalogue:\n"))+"Canonical editable catalogue:\n"+catalogue(Collections.singleton("domesticBlocks"));
            String coverageUser=user+"\nOne bounded coverage check: the original response omitted domesticBlocks or returned ordinary null, although this part contains a direct residential-scope statement. "
                    +"Return only domesticBlocks, with the exact source passage supporting its Boolean value. An omitted answer is not automatically Yes. "
                    +"Foundation or combined-foundation classification and construction of domestic or residential blocks are independent inputs. A pending foundation classification does not make a stated domestic construction scope pending; evaluate the residential-scope statement separately. "
                    +"An actual project-scope description can support an editable suggestion without a separate domestic-construction approval decision; later UI adoption is separate. "
                    +"Questions, pending decisions, template conditions, neighbouring existing buildings and unselected alternatives do not establish project scope. "
                    +"Return null with explanation if this source cannot support the value. Do not answer or replace any other input.";
            ExtractionAttemptVO coverage=dispatch(trace,part,"coverage",coverageSystem,coverageUser);
            try {
                JsonNode items=decode(coverage.getRawResponse());coverage.setStatus("completed");int index=0;
                for(JsonNode item:items) {
                    ExtractionDecisionVO decision=decide(part,coverage,index++,item,originalSource);
                    if(!"domesticBlocks".equals(decision.getKey())) {decision.setStatus("rejected");decision.getCodes().add(0,"coverage_key_not_allowed");}
                    trace.getDecisions().add(decision);
                }
            } catch(RuntimeException invalid) {coverage.setStatus("failed");coverage.setErrorCode("invalid_json");}
        }
    }

    /** A missing output is searched once in original source context, never guessed or recursively repaired. */
    public void recall(ExtractTraceVO trace,String system,String configuredUser,Map<Long,String> sources,Map<String,Object> adopted) {
        int dispatched=0;List<ExtractionContextVO> searched=new ArrayList<>();List<Long> searchedSources=new ArrayList<>();
        Map<Long,Integer> offsets=new HashMap<>();
        Map<Long,List<ExtractionContextVO>> sourcePlans=new LinkedHashMap<>();
        Map<ExtractionContextVO,ExtractionPartVO> owners=new IdentityHashMap<>();
        for(ExtractionPartVO part:trace.getParts()) {
            int start=offsets.getOrDefault(part.getSourceDocumentId(),0);offsets.put(part.getSourceDocumentId(),start+part.getSourceText().length());
            String source=sources.get(part.getSourceDocumentId());if(source==null)continue;
            List<ExtractionContextVO> plans=sourcePlans.computeIfAbsent(part.getSourceDocumentId(),key->new ArrayList<>());
            for(DraftBlueprint.InputSpec spec:DraftBlueprint.INPUTS) {
                if(spec.hidden)continue;List<Integer> localCues=DraftSourceContext.cues(part.getSourceText(),spec);if(localCues.isEmpty())continue;
                List<ExtractionDecisionVO> local=new ArrayList<>();
                for(ExtractionDecisionVO decision:trace.getDecisions())if(part.getPartId().equals(decision.getPartId())&&spec.key.equals(decision.getKey()))local.add(decision);
                if(local.stream().anyMatch(decision->"accepted".equals(decision.getStatus())))continue;
                int lastPendingCue=-1,pendingOffset=0;
                // A validated pending quote identifies its own passage even if its wording lacks catalogue cues.
                for(String passage:part.getSourceText().split("\\n",-1)) {
                    if(pendingCue(passage.trim(),local))lastPendingCue=pendingOffset;
                    pendingOffset+=passage.length()+1;
                }
                for(int localCue:localCues) {
                // A pending observation belongs to its actual passage, not to every later topic in the core part.
                if(localCue<=lastPendingCue) {
                    ExtractionContextVO pending=DraftSourceContext.recall(source,start+lastPendingCue,Collections.singletonList(spec.key),"ordinary_null");
                    pending.setStopReason("explicit_pending");part.setContext(pending);continue;
                }
                String trigger="missing_output";
                if(local.stream().anyMatch(decision->"unanswered".equals(decision.getStatus())))trigger="ordinary_null";
                else if(!local.isEmpty()&&"missing_output".equals(trigger))trigger="rejected_candidate";
                ExtractionContextVO plan=DraftSourceContext.recall(source,start+localCue,new ArrayList<>(Collections.singleton(spec.key)),trigger);
                addRecallPlan(plans,plan,source,part,owners);
                }
            }
        }
        // All known plans of one original document are combined before the first recall is dispatched.
        for(Map.Entry<Long,List<ExtractionContextVO>> plannedSource:sourcePlans.entrySet()) {
            String source=sources.get(plannedSource.getKey());
            for(ExtractionContextVO context:plannedSource.getValue()) {
            ExtractionPartVO part=owners.get(context);
            Set<String> allowed=new LinkedHashSet<>(context.getKeys());
            Map<String,Object> updatedConditions=recallConditions(trace,adopted);
            allowed.removeIf(key->Boolean.FALSE.equals(DraftBusinessRules.condition(DraftBlueprint.find(key).schema.get("condition"),updatedConditions)));
            Set<String> inactive=new LinkedHashSet<>(context.getKeys());inactive.removeAll(allowed);
            if(!inactive.isEmpty())part.setContext(new ExtractionContextVO(context.getTrigger(),new ArrayList<>(inactive),context.getSourceStart(),context.getSourceEnd(),context.getSourceText(),"inactive"));
            if(allowed.isEmpty())continue;
            context.setKeys(new ArrayList<>(allowed));
            boolean overlapping=false;
            for(int i=0;i<searched.size();i++)if(part.getSourceDocumentId().equals(searchedSources.get(i))&&
                    context.getSourceStart()<searched.get(i).getSourceEnd()&&context.getSourceEnd()>searched.get(i).getSourceStart()&&
                    searched.get(i).getKeys().containsAll(allowed))overlapping=true;
            if(overlapping)continue;
            if(context.getStopReason()!=null){part.setContext(context);continue;}
            if(dispatched>=8){context.setStopReason("budget_exhausted");part.setContext(context);continue;}
            searched.add(context);searchedSources.add(part.getSourceDocumentId());dispatched++;
            String recallSystem=system.substring(0,system.lastIndexOf("Canonical editable catalogue:\n"))+"Canonical editable catalogue:\n"+catalogue(allowed);
            ExtractionPartVO window=new ExtractionPartVO(part.getPartId(),part.getSourceDocumentId(),part.getFileName(),part.getSourceHash(),part.getPartIndex(),part.getSourceText(),part.getAttempts());window.setContext(context);
            String user=userPrompt(configuredUser,window)+"\nOne bounded context recall. Allowed keys: "+JsonUtils.write(allowed)
                    +". The previous model output did not provide a usable local candidate. Inspect the complete original passage and its neighbouring instruction. "
                    +"Return only source-supported values for these keys, or null with an exact unresolved-source quote when expressly pending. Never infer an answer from catalogue labels or a failed search. No further retry is permitted.";
            user+=" For collections, confirmed absence supports [] only for the stated field and scope; pending requirements or insufficient facts remain null with their original explanation and quotation. 'No requirement can be established from the available facts' is null, not []. Return concrete supplied items as a list. Check the full original paragraph, including qualifications around a short negative quote.";
            ExtractionAttemptVO attempt;
            try{attempt=dispatch(trace,window,"recall",recallSystem,user);}
            catch(RuntimeException failure) {
                part.getAttempts().get(part.getAttempts().size()-1).getContext().setStopReason("model_call_failed");
                if(ai.explicitProfileSelected())throw failure;continue;
            }
            try {
                JsonNode items=decode(attempt.getRawResponse());attempt.setStatus("completed");int index=0;boolean accepted=false,pending=false;
                for(JsonNode item:items) {
                    ExtractionDecisionVO decision=decide(part,attempt,index++,item,source);
                    if(!allowed.contains(decision.getKey())){decision.setStatus("rejected");decision.getCodes().add(0,"recall_key_not_allowed");}
                    trace.getDecisions().add(decision);accepted|="accepted".equals(decision.getStatus());pending|=allowed.contains(decision.getKey())&&decision.getCodes().contains("source_unresolved");
                }
                attempt.getContext().setStopReason(accepted?"candidate_found":pending?"explicit_pending":"no_supported_candidate");
            } catch(RuntimeException invalid) {attempt.setStatus("failed");attempt.setErrorCode("invalid_json");attempt.getContext().setStopReason("invalid_json");}
            }
        }
    }

    /** Merge known contained windows before any dispatch; neighbouring independent topics stay separate. */
    private void addRecallPlan(List<ExtractionContextVO> plans,ExtractionContextVO plan,String source,ExtractionPartVO owner,
                               Map<ExtractionContextVO,ExtractionPartVO> owners) {
        int position=plans.size();boolean merged;
        do {
            merged=false;
            for(int i=0;i<plans.size();i++) {
                ExtractionContextVO existing=plans.get(i);
                boolean same=existing.getSourceStart()==plan.getSourceStart()&&existing.getSourceEnd()==plan.getSourceEnd();
                boolean contained=existing.getSourceEnd()==plan.getSourceEnd()&&
                        ((existing.getSourceStart()<=plan.getSourceStart()&&existing.getSourceEnd()>=plan.getSourceEnd())||
                        (plan.getSourceStart()<=existing.getSourceStart()&&plan.getSourceEnd()>=existing.getSourceEnd()));
                int left=Math.min(existing.getSourceStart(),plan.getSourceStart()),right=Math.max(existing.getSourceEnd(),plan.getSourceEnd());
                if(!contained||(!same&&(existing.getStopReason()!=null||plan.getStopReason()!=null||right-left>DraftSourceContext.WINDOW_BUDGET)))continue;
                Set<String> keys=new LinkedHashSet<>(existing.getKeys());keys.addAll(plan.getKeys());plan.setKeys(new ArrayList<>(keys));
                if("ordinary_null".equals(existing.getTrigger()))plan.setTrigger("ordinary_null");
                if(existing.getStopReason()!=null)plan.setStopReason(existing.getStopReason());
                plan.setSourceStart(left);plan.setSourceEnd(right);plan.setSourceText(source.substring(left,right));
                ExtractionPartVO previousOwner=owners.remove(existing);
                if(previousOwner.getPartIndex()<owner.getPartIndex())owner=previousOwner;
                position=Math.min(position,i);plans.remove(i);merged=true;break;
            }
        } while(merged);
        plans.add(Math.min(position,plans.size()),plan);
        owners.put(plan,owner);
    }

    private Map<String,Object> recallConditions(ExtractTraceVO trace,Map<String,Object> adopted) {
        Map<String,Object> values=new LinkedHashMap<>();Set<String> conflicts=new HashSet<>();
        for(ExtractionDecisionVO decision:trace.getDecisions())if("accepted".equals(decision.getStatus())) {
            Object value=DraftAdoption.decode(DraftBlueprint.find(decision.getKey()),decision.getNormalizedValue());
            if(values.containsKey(decision.getKey())&&!Objects.equals(values.get(decision.getKey()),value))conflicts.add(decision.getKey());
            else values.put(decision.getKey(),value);
        }
        conflicts.forEach(values::remove);values.putAll(adopted);return values;
    }

    private boolean pendingCue(String passage,List<ExtractionDecisionVO> local) {
        return passage.toLowerCase(Locale.ROOT).matches("(?s).*\\b(pending|not supplied|not provided|blank|outstanding|unknown|unresolved|undetermined)\\b.*")&&
                local.stream().anyMatch(decision->decision.getCodes().contains("source_unresolved")&&DraftEvidenceQuotes.present(decision.getSourceQuote(),passage));
    }

    private ExtractionAttemptVO dispatch(ExtractTraceVO trace,ExtractionPartVO part,String kind,String system,String user) {
        ExtractionAttemptVO attempt=new ExtractionAttemptVO(part.getAttempts().size()+1,kind,system,user,null,"dispatched",null);part.getAttempts().add(attempt);
        if(part.getContext()!=null) {
            ExtractionContextVO context=part.getContext();
            attempt.setContext(new ExtractionContextVO(context.getTrigger(),context.getKeys(),context.getSourceStart(),context.getSourceEnd(),context.getSourceText(),context.getStopReason()));
        }
        try {
            String raw=ai.complete(system,user);attempt.setRawResponse(raw);trace.getRawResponses().add(raw);return attempt;
        } catch(RuntimeException failure) {attempt.setStatus("failed");attempt.setErrorCode("model_call_failed");throw failure;}
    }

    private JsonNode decode(String raw) {
        JsonNode items=unwrap(JsonUtils.parse(JsonUtils.extractJson(raw)),0);
        if(!items.isArray())throw new IllegalArgumentException("Expected JSON array");
        return items;
    }

    private JsonNode unwrap(JsonNode node,int depth) {
        if(depth>20)throw new IllegalArgumentException("Nested output exceeds supported wrappers");
        String[] wrappers={"items","findings","variables","results","data","list"};
        if(node.isObject())for(String key:wrappers)if(node.path(key).isArray())return unwrap(node.get(key),depth+1);
        if(node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode flat=JsonUtils.mapper().createArrayNode();
            for(JsonNode item:node) {
                boolean wrapped=false;
                if(item.isObject())for(String key:wrappers)if(item.path(key).isArray()){flat.addAll((com.fasterxml.jackson.databind.node.ArrayNode)unwrap(item.get(key),depth+1));wrapped=true;break;}
                if(!wrapped)flat.add(item);
            }
            return flat;
        }
        return node;
    }

    private ExtractionDecisionVO decide(ExtractionPartVO part,ExtractionAttemptVO attempt,int index,JsonNode item,String originalSource) {
        String supplied=attempt.getContext()==null?part.getSourceText():attempt.getContext().getSourceText();
        String key=item.path("key").asText();JsonNode raw=item.get("value");
        String quote=item.path("sourceQuote").isTextual()?item.path("sourceQuote").asText():null;
        Double confidence=item.path("confidence").isNumber()?item.path("confidence").asDouble():null;
        ExtractionDecisionVO decision=new ExtractionDecisionVO(part.getPartId(),attempt.getAttemptIndex(),index,key,
                raw,null,quote,item.path("reason").asText(),confidence,"rejected",new ArrayList<>());
        DraftBlueprint.InputSpec spec=DraftBlueprint.find(key);
        if(spec==null){decision.getCodes().add("unknown_key");return decision;}
        if(spec.hidden){decision.getCodes().add("hidden_key");return decision;}
        decision.setKey(spec.key);
        String value=raw==null||raw.isNull()?null:raw.isTextual()?raw.asText():JsonUtils.write(raw);
        if(value==null||value.trim().isEmpty()||"unknown".equalsIgnoreCase(value.trim())) {
            unanswered(supplied,spec,decision);return decision;
        }
        Object parsed=("text".equals(spec.kind)||"date".equals(spec.kind))&&raw.isTextual()?value:DraftBusinessRules.parse(value);
        if(parsed==null||"unknown".equals(parsed)||"contract".equals(spec.kind)&&parsed instanceof Map&&
                !DraftBusinessRules.answered(DraftBusinessRules.asMap(parsed).get("number"))&&!DraftBusinessRules.answered(DraftBusinessRules.asMap(parsed).get("title"))) {
            unanswered(supplied,spec,decision);return decision;
        }
        try{
            if(!shapeValid(spec,parsed))throw new IllegalArgumentException("Invalid candidate shape");
            boolean canRecover=confidence!=null&&Double.isFinite(confidence)&&confidence>=0.70&&confidence<=1;
            DraftCandidateEvidenceRecovery.Result evidence=DraftCandidateEvidenceRecovery.assess(spec,parsed,quote,supplied,originalSource,canRecover);
            parsed=evidence.value;quote=evidence.quote;
            decision.setSourceQuote(quote);decision.getCodes().addAll(evidence.codes);
            value=parsed instanceof String?(String)parsed:JsonUtils.write(parsed);
            Object supported="billNos".equals(spec.key)?supportedBillMetadata(parsed,supplied,decision):parsed;
            decision.setNormalizedValue(DraftInputRules.normalizeSuggestion(spec,"billNos".equals(spec.key)?JsonUtils.write(supported):value));
        }
        catch(RuntimeException invalid){decision.getCodes().add("invalid_value_shape");}
        if(decision.getNormalizedValue()!=null&&!literalIdentifiersPresent(spec,parsed,supplied))decision.getCodes().add("lexical_support_missing");
        if(confidence==null)decision.getCodes().add("confidence_missing");
        else if(!Double.isFinite(confidence)||confidence<0||confidence>1)decision.getCodes().add("confidence_out_of_range");
        else if(confidence<0.70)decision.getCodes().add("confidence_below_threshold");
        if(JsonUtils.isBlankText(quote))decision.getCodes().add("quote_missing");
        else if(!DraftEvidenceQuotes.present(supplied,quote))decision.getCodes().add("quote_not_in_part");
        else if("billNos".equals(spec.key)&&parsed instanceof List&&((List<?>)parsed).isEmpty()&&!noBillsSupported(quote))decision.getCodes().add("quote_value_mismatch");
        else if("subcontractors".equals(spec.key)&&parsed instanceof List&&!selectedTradesSupported((List<?>)parsed,quote))decision.getCodes().add("quote_value_mismatch");
        else if("domesticBlocks".equals(spec.key)&&!Objects.equals(DraftBusinessRules.truth(parsed),domesticScope(quote)))decision.getCodes().add("quote_value_mismatch");
        else if("volumetricPrecastComponents".equals(spec.key)&&DraftCandidateGrounding.con8Contradicted(parsed,quote))decision.getCodes().add("quote_value_mismatch");
        else if("date".equals(spec.kind)&&!DraftCandidateGrounding.dateSupported(spec.key,String.valueOf(parsed),quote))decision.getCodes().add("quote_value_mismatch");
        else if("projectArchitectPhone".equals(spec.key)&&!DraftCandidateGrounding.phoneSupported(String.valueOf(parsed),quote))decision.getCodes().add("quote_value_mismatch");
        else if(!"projectArchitectPhone".equals(spec.key)&&!DraftCandidateGrounding.directLiteralSupported(spec,parsed,quote))decision.getCodes().add("quote_value_mismatch");
        else if("otherTenderingArrangement".equals(spec.key)&&returnMediaForExistingTender(quote))decision.getCodes().add("quote_value_mismatch");
        if(("list".equals(spec.kind)||"multiselect".equals(spec.kind))&&parsed instanceof List&&((List<?>)parsed).isEmpty()&&
                !confirmedEmptyListSupported(spec,supplied,quote))decision.getCodes().add("unsupported_empty_list");
        if(attempt.getContext()!=null&&!DraftSourceContext.completeQuote(originalSource,attempt.getContext(),quote,spec.kind))decision.getCodes().add("context_incomplete_source");
        if(decision.getCodes().stream().allMatch(code->code.startsWith("bill_metadata_unsupported:")||DraftCandidateEvidenceRecovery.diagnostic(code)))decision.setStatus("accepted");
        return decision;
    }

    /** Empty is an absence claim, requiring field-specific evidence in its complete original paragraph. */
    private boolean confirmedEmptyListSupported(DraftBlueprint.InputSpec spec,String supplied,String quote) {
        if(!DraftEvidenceQuotes.present(supplied,quote))return false;
        String[] context=quotedOriginalParagraphs(supplied,quote).split("\\R|;|(?<=[.!?])\\s+");
        String absence,subject;
        switch(spec.key) {
            case "billNos":
                if(!noBillsSupported(quote))return false;
                subject="bills|bill\\s+(?:numbers?|descriptions?|lists?|schedules?|[A-Za-z0-9]+(?=\\s*:))";
                absence="no\\s+bills?\\s+(?:(?:are|will be)\\s+)?(?:included|applicable|required|exist)|(?:project|contract|tender|works)\\b[^.!?\\r\\n]{0,40}\\b(?:has|includes?|requires?)\\s+no\\s+bills?|bills?\\s+(?:list|schedule)\\s*[:|=]\\s*none|no\\s+bills?";break;
            case "subcontractors":
                if(!selectedTradesSupported(Collections.emptyList(),quote))return false;
                subject="(?:selected|nominated|specialist|subcontract)\\s+(?:subcontract\\s+)?trades?|subcontractors";
                absence="no\\s+(?:specialist\\s+)?(?:subcontract\\s+)?trades?\\s+(?:(?:are|will be)\\s+)?(?:selected|nominated|required|included)|selected\\s+(?:subcontract\\s+)?(?:trade\\s+)?list\\s*[:|=]\\s*none|no\\s+(?:specialist\\s+)?(?:subcontract\\s+)?trades?";break;
            case "additionalSubmissions":
                subject="additional\\s+(?:project[- ]specific\\s+)?(?:general\\s+)?(?:tender\\s+)?(?:submission\\s+requirements|requirements|submissions)";
                absence="no\\s+additional\\s+(?:tender\\s+)?(?:requirements|submissions)\\s+(?:(?:are|will be)\\s+(?:required|applicable)|apply)|additional\\s+(?:tender\\s+)?(?:requirements|submissions)\\s*[:|=]\\s*none";break;
            case "designResponsibilities":
                subject="design\\s+(?:(?:and|or)\\s+execution\\s+)?responsibilit(?:y|ies)|execution\\s+responsibilit(?:y|ies)";
                absence="no\\s+(?:contractor\\s+)?design\\s+(?:and|or)\\s+execution\\s+responsibilit(?:y|ies)\\s+(?:apply|(?:is|are)\\s+(?:assigned|required))|design\\s+and\\s+execution\\s+responsibilit(?:y|ies)(?:\\s+by\\s+component)?\\s*[:|=]\\s*none";break;
            case "oldValuableTrees":
                subject="old\\s+and\\s+valuable\\s+trees|OVT";
                absence="no\\s+old\\s+and\\s+valuable\\s+trees\\s+(?:are\\s+(?:present|identified|to\\s+be\\s+preserved)|to\\s+preserve)|old\\s+and\\s+valuable\\s+trees(?:\\s+to\\s+preserve)?\\s*[:|=]\\s*none";break;
            case "pseSubmissions":
                subject="PSE\\s+(?:requirements|submissions)";
                absence="no\\s+PSE\\s+(?:requirements|submissions)\\s+(?:(?:are|will be)\\s+(?:required|applicable)|apply)|PSE\\s+(?:requirements|submissions)(?:\\s+for\\s+domestic\\s+CON8)?\\s*[:|=]\\s*none";break;
            case "sections":
                subject="sections";
                absence="(?:the\\s+)?works\\s+(?:are\\s+not|will\\s+not\\s+be)\\s+divided\\s+into\\s+sections|no\\s+sections\\s+(?:are\\s+(?:designated|specified)|apply)|sections\\s*[:|=]\\s*(?:works\\s+type\\s+and\\s+location\\s*[:|=]\\s*)?(?:explicitly\\s+)?none";break;
            case "siteVisitRestrictions":
                subject="(?:(?:actual\\s+)?inspection|site\\s+visit)\\s+restrictions";
                absence="no\\s+(?:actual\\s+)?(?:inspection|site\\s+visit)\\s+restrictions\\s+(?:apply|(?:are|will be)\\s+(?:imposed|required))|(?:actual\\s+)?(?:inspection|site\\s+visit)\\s+restrictions\\s*[:|=]\\s*none";break;
            default:return false;
        }
        String listSubject=subject;
        if("oldValuableTrees".equals(spec.key))listSubject="(?:"+subject+")(?:\\s+to\\s+be\\s+preserved)?";
        else if("designResponsibilities".equals(spec.key))listSubject="(?:"+subject+")(?:\\s+by\\s+component)?";
        else if("pseSubmissions".equals(spec.key))listSubject="(?:"+subject+")(?:\\s+for\\s+domestic\\s+CON8)?";
        absence+="|"+explicitEmptyList(listSubject);
        String scope="(?:\\s+(?:to|for|in|on)\\s+(?:(?:this|the|current)\\s+)?(?:project|tender|contract|works|list|site))?";
        java.util.regex.Pattern confirmed=java.util.regex.Pattern.compile("(?i)\\b("+absence+")\\b"+scope+"\\s*[.!]?\\s*$");
        // Read all named qualifications of this collection before admitting a short absence quote.
        for(String passage:context) {
            boolean sameField=java.util.regex.Pattern.compile("(?i)\\b(?:"+subject+")\\b").matcher(passage).find()||
                    "additionalSubmissions".equals(spec.key)&&passage.matches("(?is)^\\s*(?:however|but|nevertheless)[,\\s]+(?:provide|submit)\\b.*\\b(?:with|for)\\s+(?:this|the)\\s+tender\\b.*");
            boolean suppliedItem=java.util.regex.Pattern.compile("(?i)\\b(?:requires?|required|provide|submit|includes?|included|assigned|selected|nominated|designated|present|identified|preserve|imposed)\\b|[:|=]\\s*(?!none\\b)\\S").matcher(passage).find();
            java.util.regex.Matcher absenceClaim=confirmed.matcher(passage);boolean hasClaim=absenceClaim.find();
            if(sameField&&(emptyListUnresolved(passage)||hasClaim&&unassertedEmptyClaim(passage,absenceClaim.start())||!hasClaim&&suppliedItem))return false;
        }
        for(int i=0;i<context.length;i++) {
            String passage=context[i];if(emptyListUnresolved(passage))continue;
            java.util.regex.Matcher match=confirmed.matcher(passage);
            if(!match.find()||unassertedEmptyClaim(passage,match.start())||!DraftEvidenceQuotes.present(quote,match.group(1)))continue;
            // A linked qualification belongs to this statement; an unrelated field's unknown does not.
            if(i+1<context.length&&context[i+1].trim().matches("(?is)^(?:(?:however|but|nevertheless)[,\\s]+)?(?:this|that|it|the above)\\b.*")&&emptyListUnresolved(context[i+1]))continue;
            return true;
        }
        return false;
    }

    private String explicitEmptyList(String subject) {
        return "(?:adopted\\s+)?list\\s+of\\s+(?:"+subject+")\\s+is\\s+explicitly\\s+empty";
    }

    private boolean unassertedEmptyClaim(String passage,int claimStart) {
        // Inspect only the prefix governing this matched claim, not independent cautions in the paragraph.
        return passage.substring(0,claimStart).matches("(?is).*\\b(?:not\\s+(?:yet\\s+)?(?:the\\s+case|true|confirmed|established|agreed)\\s+that|"
                +"(?:do|does|did)\\s+not\\s+(?:assume|infer|conclude)(?:\\s+that)?|(?:have|has)\\s+not\\s+(?:yet\\s+)?(?:been\\s+)?(?:established|confirmed|agreed)\\s+that)\\s+(?:the\\s+)?$");
    }

    private boolean emptyListUnresolved(String passage) {
        return passage.contains("?")||java.util.regex.Pattern.compile("(?i)\\b(please confirm|please provide|please advise|whether|if|unless|subject to|pending|unknown|unconfirmed|undetermined|outstanding|not supplied|not provided|insufficient|unavailable|missing|proposed|proposal|to be confirmed|except|other than)\\b").matcher(passage).find();
    }

    private String quotedOriginalParagraphs(String supplied,String quote) {
        String source=DraftEvidenceQuotes.textIdentity(supplied),literal=DraftEvidenceQuotes.textIdentity(quote);
        int start=source.indexOf(literal),end=start+literal.length(),cursor=0;StringBuilder paragraphs=new StringBuilder();
        for(String paragraph:supplied.split("\\R")) {
            String identity=DraftEvidenceQuotes.textIdentity(paragraph);if(identity.isEmpty())continue;
            int from=source.indexOf(identity,cursor),to=from+identity.length();cursor=to;
            if(from<end&&to>start)paragraphs.append(paragraph).append('\n');
            if(to>=end)break;
        }
        return paragraphs.toString();
    }

    private boolean noBillsSupported(String quote) {
        String text=quote.toLowerCase(Locale.ROOT);
        if(text.contains("?")||text.matches("(?s).*\\b(please confirm|please provide|whether|if|pending|unknown|not supplied|not provided|see|refer)\\b.*")||
                text.matches("(?s).*\\bin\\s+(?:this|the)\\s+(?:email|message|source|part|summary)\\b.*")||
                text.matches("(?s).*\\b[1-9]\\d*\\s+(entries|bills?)\\b.*"))return false;
        return java.util.regex.Pattern.compile("(?i)\\b"+explicitEmptyList("bills|bill\\s+(?:numbers?|descriptions?|lists?|schedules?)")+"\\b").matcher(quote).find()||
                text.matches("(?s).*\\bno\\s+bills?\\s+(?:(?:are|will be)\\s+)?(?:included|applicable|required|exist)\\b.*")||
                text.matches("(?s).*\\b(?:project|contract|tender|works)\\b[^.!?\\r\\n]{0,40}\\b(?:has|includes?|requires?)\\s+no\\s+bills?\\b.*")||
                text.matches("(?s).*\\bbills?\\s+(?:list|schedule)\\s*[:|=]\\s*none\\b.*")||text.matches("\\s*no\\s+bills?\\s*[.!]?\\s*");
    }

    private boolean returnMediaForExistingTender(String quote) {
        String text=quote.toLowerCase(Locale.ROOT);
        if(text.matches("(?s).*\\b(single[- ]envelope|one[- ]envelope|non[- ]two[- ]envelope|instead of|alternative (?:tender|procedure))\\b.*"))return false;
        return text.matches("(?s).*\\btwo[- ]envelope\\b.*")&&
                text.matches("(?s).*\\b(hard[- ]?copy|dvd[- ]rom|submission medium|return media|return medium)\\b.*");
    }

    /** Bounded guard for direct residential scope, not a general Boolean entailment checker. */
    private Boolean domesticScope(String quote) {
        Boolean supported=null;
        for(String clause:quote.toLowerCase(Locale.ROOT).split("\\R|(?<=[.!?])\\s+")) {
            if(!java.util.regex.Pattern.compile("\\b(domestic|residential)\\s+(blocks?|buildings?)\\b").matcher(clause).find())continue;
            if(clause.contains("?")||clause.matches("(?s).*\\b(whether|please confirm|if|would|could|might|option|pending|unconfirmed|template|adjacent|neighbouring|neighboring|existing)\\b.*"))continue;
            boolean no=clause.matches("(?s).*\\b(no|not|without|exclude|excluded)\\b.{0,80}\\b(domestic|residential)\\b.*")||
                    clause.matches("(?s).*\\b(domestic|residential)\\s+(?:blocks?|buildings?).{0,50}\\b(no|not included|excluded|outside)\\b.*");
            boolean yes=clause.matches("(?s).*\\b(describes|includes?|comprises?|comprise|consists of|construction of|constructs?)\\b.*")||
                    clause.matches("(?s).*\\b(domestic|residential)\\s+blocks?\\s+(?:construction\\s*)?[:|=]\\s*yes\\b.*");
            if(!no&&!yes)continue;
            boolean answer=!no;
            if(supported!=null&&supported!=answer)return null;
            supported=answer;
        }
        return supported;
    }

    private boolean selectedTradesSupported(List<?> selected,String quote) {
        String text=quote.toLowerCase(Locale.ROOT);
        if(text.contains("?")||text.matches("(?s).*\\b(please confirm|whether|not selected|pending confirmation)\\b.*"))return false;
        if(selected.isEmpty())return java.util.regex.Pattern.compile("(?i)\\b"+explicitEmptyList("(?:selected|nominated|specialist|subcontract)\\s+(?:subcontract\\s+)?trades?|subcontractors")+"\\b").matcher(quote).find()||
                text.matches("(?s).*\\bno\\s+(?:specialist\\s+)?(?:subcontract\\s+)?trades?\\b.*")||
                text.matches("(?s).*\\bselected\\s+(?:subcontract\\s+)?(?:trade\\s+)?list\\s*[:|=]\\s*none\\b.*");
        boolean listContext=java.util.regex.Pattern.compile("(?i)\\b(?:selected trades|building services trades|specialist\\s+sub[- ]?contract(?:ors?|s?)|sub[- ]?contract\\s+list|selection option\\s*[|:]\\s*selected)\\b").matcher(quote).find();
        for(Object item:selected) {
            String trade=String.valueOf(item);
            String suffix="Fire services".equals(trade)?"(?!\\s+and\\s+water\\s+pump)":"Lift".equals(trade)?"(?!\\s+and\\s+escalator)":"";
            java.util.regex.Pattern pattern=java.util.regex.Pattern.compile("(?i)\\b"+java.util.regex.Pattern.quote(trade)+"\\b"+suffix);
            boolean supported=false;
            for(String clause:quote.split("\\R|;|\\.\\s+")) {
                java.util.regex.Matcher match=pattern.matcher(clause);
                while(match.find()) {
                    String before=clause.substring(0,match.start()).toLowerCase(Locale.ROOT);
                    String after=clause.substring(match.end()).toLowerCase(Locale.ROOT);
                    boolean negative=clause.toLowerCase(Locale.ROOT).matches("(?s).*\\b(?:does not include|do not include|are excluded|are not included|are not selected|is excluded)\\b.*")||
                            before.matches("(?s).*\\b(no|not|exclude|excluding|excluded)\\b[^,;:.]{0,70}$")||
                            after.matches("(?s)^\\s*(?:works\\s*)?[|:= -]*\\s*(?:(?:are|is)\\s+)?(?:no|not|excluded|outside)\\b.*");
                    if(!negative&&(listContext||after.matches("(?s)^\\s*(?:works\\s*)?[|:= -]*\\s*yes\\b.*")))supported=true;
                    else return false;
                }
            }
            if(!supported)return false;
        }
        return true;
    }

    private Object supportedBillMetadata(Object value,String source,ExtractionDecisionVO decision) {
        List<Object> supported=new ArrayList<>();
        for(Object item:DraftBusinessRules.list(value)) {
            Map<String,Object> row=new LinkedHashMap<>(DraftBusinessRules.asMap(item));
            String number=String.valueOf(row.get("number")),description=String.valueOf(row.get("description"));
            List<String> contexts=new ArrayList<>();
            for(String line:source.split("\\R|(?i)(?=\\bBill\\s*(?:No\\.?\\s*)?\\d+\\b)")) {
                if(line.contains("?")||line.toLowerCase(Locale.ROOT).matches("(?s).*\\b(if|whether|please confirm|would|could)\\b.*"))continue;
                String rowStart="(?i)^\\s*"+java.util.regex.Pattern.quote(number)+"\\s*[|:]";
                String billNumber="(?i)\\bBill\\s*(?:No\\.?\\s*)?"+java.util.regex.Pattern.quote(number)+"\\b";
                if((java.util.regex.Pattern.compile(rowStart).matcher(line).find()||java.util.regex.Pattern.compile(billNumber).matcher(line).find())&&
                        DraftEvidenceQuotes.present(line,description)) {
                    int after=line.toLowerCase(Locale.ROOT).indexOf(description.toLowerCase(Locale.ROOT))+description.length();
                    if(after>=description.length())contexts.add(line.substring(after));
                }
            }
            for(String field:Arrays.asList("type","purpose","trade","placement","placementText")) {
                if(!DraftBusinessRules.answered(row.get(field)))continue;
                String cell=String.valueOf(row.get(field));
                if(contexts.stream().noneMatch(context->billMetadataSupported(field,cell,context))) {
                    row.remove(field);decision.getCodes().add("bill_metadata_unsupported:"+number+":"+field);
                }
            }
            supported.add(row);
        }
        return supported;
    }

    private boolean billMetadataSupported(String field,String value,String context) {
        String text=context.toLowerCase(Locale.ROOT);
        if(text.contains("?")||text.matches("(?s).*\\b(not|pending|unknown|unconfirmed|whether|if|would|could|please confirm)\\b.*"))return false;
        if("type".equals(field)) {
            String type="SOR".equals(value)?"(?:SOR|schedules? of rates)":"(?:BQ|bills? of quantities)";
            return java.util.regex.Pattern.compile("(?i)\\b"+type+"\\b").matcher(context).find();
        }
        String prefix="purpose".equals(field)?"(?:purpose|role)":"trade".equals(field)?"(?:trade|scope)":"(?:placement|distribution|issue)";
        String cell=value;
        if("DiscA".equals(cell))cell="Disc A";
        if("DiscB".equals(cell))cell="Disc B";
        return java.util.regex.Pattern.compile("(?i)\\b"+prefix+"\\s*[:=]\\s*"+java.util.regex.Pattern.quote(cell)+"(?:\\b|$)").matcher(context).find();
    }

    private void unanswered(String supplied,DraftBlueprint.InputSpec spec,ExtractionDecisionVO decision) {
        decision.setStatus("unanswered");decision.getCodes().add("value_unanswered");
        String quote=decision.getSourceQuote();
        if(!DraftEvidenceQuotes.present(supplied,quote)) {
            if(!JsonUtils.isBlankText(quote))decision.getCodes().add("quote_not_in_part");
            return;
        }
        String text=quote.toLowerCase(Locale.ROOT);
        for(String clause:text.split("\\R|;|\\.\\s+|\\bbut\\b")) {
            if(clause.contains("?")||clause.matches("(?s).*\\b(please confirm|whether|not pending|not unknown|not blank|no longer unresolved)\\b.*")||
                    !java.util.regex.Pattern.compile("\\b(pending|not supplied|not provided|blank|outstanding|unknown|unresolved|undetermined)\\b").matcher(clause).find())continue;
            boolean topic="foundationIncluded".equals(spec.key)?clause.matches("(?s).*\\bfoundation\\b.*")||
                    text.matches("(?s).*\\bfoundation\\b.*")&&clause.matches("(?s).*\\bthat classification\\b.*"):
                    "contractPeriodMonths".equals(spec.key)?clause.matches("(?s).*\\b(duration|period|column\\s*\\(?b\\)?)\\b.*"):
                    "domesticBlocks".equals(spec.key)?clause.matches("(?s).*\\b(domestic|residential)\\s+(blocks?|buildings?)\\b.*"):
                    "otherTenderingArrangement".equals(spec.key)?clause.matches("(?s).*\\b(alternative\\s+(?:tender(?:ing)?\\s+)?(?:procedure|system|arrangement)|(?:single|one|non[- ]two)[- ]envelope\\s+(?:tender(?:ing)?\\s+)?(?:procedure|system|arrangement)|sct\\s*5\\s+replacement|replacement\\s+(?:wording\\s+)?(?:for\\s+)?sct\\s*5)\\b.*"):
                    Arrays.stream(spec.labelEn.toLowerCase(Locale.ROOT).split("[^a-z]+"))
                            .filter(word->word.length()>3&&!Arrays.asList("contract","works","includes","included","whether","with","this","project").contains(word))
                            .anyMatch(word->java.util.regex.Pattern.compile("\\b"+java.util.regex.Pattern.quote(word)+"\\b").matcher(clause).find());
            if(topic){decision.getCodes().add("source_unresolved");return;}
        }
    }

    private boolean shapeValid(DraftBlueprint.InputSpec spec,Object value) {
        if("contract".equals(spec.kind)) {
            if(!(value instanceof Map))return false;
            Map<?,?> record=(Map<?,?>)value;
            for(Object key:record.keySet())if(!Arrays.asList("number","title").contains(key))return false;
            for(Object cell:record.values())if(cell!=null&&!(cell instanceof String))return false;
            return !record.isEmpty();
        }
        if("number".equals(spec.kind)) {
            Double numeric=DraftBusinessRules.number(value);return numeric!=null&&Double.isFinite(numeric)&&numeric>=0;
        }
        if("text".equals(spec.kind)||"date".equals(spec.kind))return value instanceof String;
        if("list".equals(spec.kind)) {
            if(!(value instanceof List))return false;
            List<Object> columns=DraftBusinessRules.list(spec.schema.get("columnFields"));
            for(Object row:(List<?>)value) {
                if(row instanceof String&&columns.size()==1)continue;
                if(!(row instanceof Map)||((Map<?,?>)row).isEmpty())return false;
                Map<?,?> record=(Map<?,?>)row;
                for(Map.Entry<?,?> cell:record.entrySet()) {
                    Map<String,Object> column=null;
                    for(Object entry:columns)if(Objects.equals(DraftBusinessRules.asMap(entry).get("key"),cell.getKey()))column=DraftBusinessRules.asMap(entry);
                    if(column==null)return false;
                    if(cell.getValue()==null||cell.getValue() instanceof String&&((String)cell.getValue()).trim().isEmpty())continue;
                    String kind=String.valueOf(column.get("kind"));List<Object> options=DraftBusinessRules.list(column.get("options"));
                    if("text".equals(kind)||"date".equals(kind)){if(!(cell.getValue() instanceof String))return false;}
                    else if("number".equals(kind)){Double n=DraftBusinessRules.number(cell.getValue());if(n==null||!Double.isFinite(n)||n<0)return false;}
                    else if("boolean".equals(kind)){if(DraftBusinessRules.truth(cell.getValue())==null)return false;}
                    else if("multiselect".equals(kind)) {
                        if(!(cell.getValue() instanceof List))return false;
                        Set<Object> seen=new HashSet<>();for(Object option:(List<?>)cell.getValue())if(!seen.add(option)||!optionValid(options,option))return false;
                    } else if(!options.isEmpty()&&!optionValid(options,cell.getValue()))return false;
                }
            }
        }
        return true;
    }

    private boolean optionValid(List<Object> options,Object value) {
        if(options.isEmpty())return value instanceof String;
        for(Object option:options)if(Objects.equals(DraftBusinessRules.asMap(option).get("value"),value))return true;
        return false;
    }

    private boolean literalIdentifiersPresent(DraftBlueprint.InputSpec spec,Object value,String source) {
        if("contract".equals(spec.kind)) {
            for(String key:Arrays.asList("number","title"))if(!literalPresent(source,DraftBusinessRules.asMap(value).get(key)))return false;
        } else if("billNos".equals(spec.key)) {
            return DraftCandidateGrounding.billIdentitiesSupported(value,source);
        }
        return true;
    }

    private boolean literalPresent(String source,Object identifier) {
        return DraftCandidateGrounding.literalPresent(source,identifier);
    }

    public void describeFields(ExtractTraceVO trace,Set<String> conflicts,boolean completed) {
        List<ExtractionFieldVO> fields=new ArrayList<>();
        for(DraftBlueprint.InputSpec spec:DraftBlueprint.INPUTS)if(!spec.hidden) {
            int accepted=0,rejected=0,unanswered=0;boolean sourceUnresolved=false;List<Integer> refs=new ArrayList<>();Set<String> support=new HashSet<>();
            for(int i=0;i<trace.getDecisions().size();i++) {
                ExtractionDecisionVO decision=trace.getDecisions().get(i);if(!spec.key.equals(decision.getKey()))continue;
                refs.add(i);
                if("accepted".equals(decision.getStatus())) {
                    ExtractionPartVO origin=trace.getParts().stream().filter(part->part.getPartId().equals(decision.getPartId())).findFirst().orElse(null);
                    String valueIdentity="text".equals(spec.kind)?DraftEvidenceQuotes.textIdentity(decision.getNormalizedValue()):decision.getNormalizedValue();
                    String identity=JsonUtils.write(Arrays.asList(origin==null?decision.getPartId():origin.getSourceDocumentId(),origin==null?null:origin.getSourceHash(),valueIdentity));
                    if(support.add(identity))accepted++;
                }
                else if("unanswered".equals(decision.getStatus())){unanswered++;sourceUnresolved|=decision.getCodes().contains("source_unresolved");}else rejected++;
            }
            boolean coverage="domesticBlocks".equals(spec.key)&&trace.getParts().stream().flatMap(part->part.getAttempts().stream()).anyMatch(attempt->"coverage".equals(attempt.getKind()));
            String status=accepted>0?conflicts.contains(spec.key)?"candidate_conflict":"suggested":sourceUnresolved?"source_unresolved":
                    completed&&coverage?"coverage_unresolved":rejected>0?"rejected":unanswered>0?"model_unanswered":completed?"no_candidate":"not_assessed";
            fields.add(new ExtractionFieldVO(spec.key,status,accepted,rejected,unanswered,refs));
        }
        trace.setFields(fields);
    }
}
