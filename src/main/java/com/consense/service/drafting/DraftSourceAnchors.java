package com.consense.service.drafting;

import java.util.*;

/** Substantive target anchors are shared; each edit handler keeps its own guidance treatment. */
public final class DraftSourceAnchors {
    private DraftSourceAnchors() { }
    private static final Map<String,Target> TARGETS=new LinkedHashMap<>();
    static {
        target("NTT-1E-FACADE",new int[]{75},96);
        target("NTT-5F-EXCISION",new int[]{243},244);
        target("NTT-4B-PROCEDURE",new int[]{184});
        target("NTT-5D-PROCEDURE",new int[]{227});
        target("NTT-3AB-PROCEDURE",new int[]{140,142});
        target("NTT-3C-L10PRO",new int[]{166},172,173);
        target("NTT-3C-PAPER",new int[]{168},178);
        reference("NTT-10-BOND-REFERENCE",new int[]{484},486);
        reference("NTT-10-SCC-REFERENCE",new int[]{484});
        reference("NTT-13-SUBCONTRACT",new int[]{546},547);
        reference("NTT-13-SCC-REFERENCE",new int[]{546});
        reference("NTT-16-HOMES-REFERENCE",new int[]{606});
        target("sct-envelope-foundation",new int[]{627,629});
        target("sct-envelope-site-formation",new int[]{630,631});
        target("sct-envelope-domestic",new int[]{632,633});
        target("sct-envelope-special-payment",new int[]{634,635});
        target("sct-foundation-assessment",new int[]{846},848);
        target("sct-site-formation-submissions",new int[]{849,851,854,856});
        target("sct-domestic-payment-proposal",new int[]{871});
        target("sct-structural-submission-signature",new int[]{901},903);
        target("sct-architect-contact",new int[]{923},924);
        target("sct-specification-inspection-address",new int[]{926},927);
        target("sct-drawings-inspection-address",new int[]{932},933);
        target("sct-site-inspection",new int[]{961},963);
        target("scc-technical-proposal-reference",new int[]{108},100);
    }
    private static void target(String id,int[] substantive,int... guidance){TARGETS.put(id,new Target(substantive,guidance,true));}
    /** These handlers have explicit manual scopes; a generic paragraph delete must not broaden them. */
    private static void reference(String id,int[] substantive,int... guidance){TARGETS.put(id,new Target(substantive,guidance,false));}
    public static Target target(String id){return TARGETS.get(id);}
    public static int[] substantive(String id){Target target=target(id);return target==null?null:target.substantive();}
    public static final class Target {
        private final int[] substantive,guidance;
        private final boolean genericManualScope;
        private Target(int[] substantive,int[] guidance,boolean genericManualScope){this.substantive=substantive.clone();this.guidance=guidance.clone();this.genericManualScope=genericManualScope;}
        public int[] substantive(){return substantive.clone();}
        public int[] guidance(){return guidance.clone();}
        public int[] manualParagraphs(){return genericManualScope?substantive():null;}
        public int paragraph(int index){return substantive[index];}
        public int guidanceParagraph(int index){return guidance[index];}
    }
}
