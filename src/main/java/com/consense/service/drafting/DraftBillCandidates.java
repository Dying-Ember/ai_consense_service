package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.CandidateVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pricing type is an explicit namespace; a missing type never invents another Bill identity. */
final class DraftBillCandidates {
    private DraftBillCandidates() { }

    static String merge(DraftBlueprint.InputSpec spec,List<CandidateVO> candidates) {
        List<String> values=new ArrayList<>();for(CandidateVO candidate:candidates)values.add(candidate.getValue());
        return mergeValues(spec,values);
    }

    static String merge(DraftBlueprint.InputSpec spec,String left,String right) {
        return mergeValues(spec,Arrays.asList(left,right));
    }

    private static String mergeValues(DraftBlueprint.InputSpec spec,List<String> values) {
        if(values.isEmpty())return "";
        Map<String,ObjectNode> typed=new LinkedHashMap<>(),untyped=new LinkedHashMap<>();
        Map<String,List<String>> namespaces=new LinkedHashMap<>();
        List<RowOrder> order=new ArrayList<>();boolean explicitEmpty=false,hasRows=false;
        List<List<RowOrder>> candidateOrders=new ArrayList<>();
        // Collect all namespaces before resolving missing types, so candidate order cannot settle ambiguity.
        for(String value:values) {
            JsonNode records=JsonUtils.parse(value);if(!records.isArray())return null;
            explicitEmpty|=records.size()==0;hasRows|=records.size()>0;
            if(explicitEmpty&&hasRows)return null;
            List<RowOrder> candidateOrder=new ArrayList<>();candidateOrders.add(candidateOrder);
            for(JsonNode record:records) {
                if(!record.isObject()||!populated(record.get("number"))||!populated(record.get("description")))return null;
                String number=number(record),type=populated(record.get("type"))?record.path("type").asText().trim():"";
                if(!type.isEmpty()&&!Arrays.asList("BQ","SOR").contains(type))return null;
                Map<String,ObjectNode> index=type.isEmpty()?untyped:typed;
                String key=type.isEmpty()?number:type+":"+number;
                candidateOrder.add(new RowOrder(type.isEmpty(),key));
                ObjectNode incoming=((ObjectNode)record).deepCopy();
                ObjectNode merged=index.containsKey(key)?mergeRow(index.get(key),incoming):incoming;
                if(merged==null)return null;
                if(!index.containsKey(key))order.add(new RowOrder(type.isEmpty(),key));
                if(!type.isEmpty()&&!index.containsKey(key))namespaces.computeIfAbsent(number,ignored->new ArrayList<>()).add(key);
                index.put(key,merged);
            }
        }
        Map<String,ObjectNode> unresolved=new LinkedHashMap<>();
        for(Map.Entry<String,ObjectNode> entry:untyped.entrySet()) {
            List<String> sameNumber=namespaces.get(entry.getKey());
            if(sameNumber==null||sameNumber.isEmpty()){unresolved.put(entry.getKey(),entry.getValue());continue;}
            if(sameNumber.size()!=1)return null;
            String key=sameNumber.get(0);ObjectNode merged=mergeRow(typed.get(key),entry.getValue());
            if(merged==null)return null;
            typed.put(key,merged);
        }
        // An optional metadata fragment must not rearrange the candidate that already lists all final identities.
        List<RowOrder> backbone=order;
        for(List<RowOrder> candidate:candidateOrders) {
            Set<String> covered=new LinkedHashSet<>();
            for(RowOrder entry:candidate)covered.add(resolved(entry,namespaces).identity());
            if(covered.size()==typed.size()+unresolved.size()){backbone=candidate;break;}
        }
        List<RowOrder> outputOrder=new ArrayList<>(backbone);outputOrder.addAll(order);
        List<ObjectNode> rows=new ArrayList<>();Set<String> emitted=new LinkedHashSet<>();
        for(RowOrder original:outputOrder) {
            RowOrder entry=resolved(original,namespaces);
            ObjectNode row=(entry.untyped?unresolved:typed).get(entry.key);
            if(row!=null&&emitted.add(entry.identity()))rows.add(row);
        }
        return DraftInputRules.normalizeSuggestion(spec,JsonUtils.write(rows));
    }

    private static final class RowOrder {
        final boolean untyped;final String key;
        RowOrder(boolean untyped,String key){this.untyped=untyped;this.key=key;}
        String identity(){return(untyped?"untyped:":"typed:")+key;}
    }

    private static RowOrder resolved(RowOrder row,Map<String,List<String>> namespaces) {
        List<String> sameNumber=row.untyped?namespaces.get(row.key):null;
        return sameNumber==null?row:new RowOrder(false,sameNumber.get(0));
    }

    private static ObjectNode mergeRow(ObjectNode first,ObjectNode second) {
        if(!number(first).equals(number(second))||!DraftEvidenceQuotes.textIdentity(first.path("description").asText())
                .equals(DraftEvidenceQuotes.textIdentity(second.path("description").asText())))return null;
        ObjectNode result=first.deepCopy();Iterator<Map.Entry<String,JsonNode>> fields=second.fields();
        while(fields.hasNext()) {
            Map.Entry<String,JsonNode> field=fields.next();String key=field.getKey();
            // IDs generated from metadata omissions are operational record IDs, not new sourced identities.
            if(Arrays.asList("id","number","description").contains(key))continue;
            JsonNode existing=result.get(key),incoming=field.getValue();
            if(populated(existing)&&populated(incoming)&&!existing.equals(incoming))return null;
            if(!populated(existing)&&populated(incoming))result.set(key,incoming.deepCopy());
        }
        if(!populated(result.get("id"))&&populated(second.get("id")))result.set("id",second.get("id"));
        return result;
    }

    private static String number(JsonNode row) {
        return DraftEvidenceQuotes.textIdentity(row.path("number").asText()).toLowerCase(Locale.ROOT);
    }

    private static boolean populated(JsonNode value) {
        return value!=null&&!value.isNull()&&(!value.isTextual()||!value.asText().trim().isEmpty());
    }
}
