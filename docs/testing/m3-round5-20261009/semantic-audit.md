# M3 本次变量识别独立语义复核

运行：`c4bb7f20-cde1-4bae-8205-1da79f2a2375`；项目：`drafting-novel-round5-20261009`。

全部 37 项自动 REVIEW 值均有原文语义支持，共核对 38 个候选引文。阅读全文包含 15 份原生 DOCX，未发现后续撤销、互相矛盾或先前值混入。

原评分器的 58/58 有值项与 21/21 留空项保持原报告不变。这里补充人工语义核验，不修改评分器或模型结果，不证明最终文稿。

| 变量 | 实际值 | 独立复核理由 | 原生出处 |
|---|---|---|---|
| `foundationIncluded` | `true` | 原文明确本合同包括图书馆和体育馆的基础工程，支持 true。 | R502 / 第 7 段 |
| `subcontractArrangement` | `"NSC"` | 原文明确采用 Nominated Sub-contracts（NSC），明确不采用 BSSSC；NSC 枚举值准确。 | R507 / 第 5 段 |
| `worksSubjectToExcision` | `true` | 原文明确东侧停车场工程受 excision 条款约束，支持 true。 | R504 / 第 5 段 |
| `siteFormationWorks` | `false` | 原文明确无 site formation works；false 正确，未把邻近 excavation 混作场地平整。 | R504 / 第 5 段 |
| `excavationPermitRequired` | `true` | 公共人行道沟槽连接明确需 excavation permit，支持 true。 | R504 / 第 5 段 |
| `subsidisedSaleFlatsProduction` | `false` | 原文明确项目不生产 subsidised sale flats，false 正确。 | R504 / 第 6 段 |
| `showFlatsRequirement` | `"none"` | 原文明示既不要求 show flats，也不要求 exhibition flats，none 枚举值准确。 | R504 / 第 6 段 |
| `domesticBlocks` | `false` | 两座设施为 non-domestic，且明确无 domestic block 或 residential accommodation，false 正确。 | R502 / 第 7 段 |
| `precastFacadeTenderBasis` | `true` | 原文明确预制混凝土外墙板作为 tender basis，true 正确。 | R504 / 第 7 段 |
| `precastFacadePermission` | `true` | 原文同时允许承包商提出替代预制外墙系统（需审批），true 正确；未混淆 tender basis 与许可。 | R504 / 第 7 段 |
| `contractorDesignedFoundations` | `false` | 第 05 文件明确所有基础由建筑师和结构工程师设计，无承包商设计基础。第 09 文件完整表格的 Contractor design 列四行均 No，而 Contractor execution 均 Yes；两候选均支持 false，未混淆设计与施工。 | R505 / 第 5 段；R509 完整原生责任表 4 行 |
| `designEstablishedAtTenderAcceptance` | `false` | 原文明示设计在 tender invitation 之前确立，而不是 tender acceptance 时，false 正确。 | R505 / 第 6 段 |
| `foundationDesignSubmittedWithTender` | `false` | 原文明示投标人不必随标提交基础设计资料，false 正确。 | R505 / 第 6 段 |
| `wtoGpaApplies` | `false` | 采购官明确确认 WTO Government Procurement 协定不适用于本合同，false 正确。 | R507 / 第 6 段 |
| `railwayProtectionAreaWorks` | `false` | 合同全部工程均不在铁路保护区，场地及沟槽也明确在区外，false 正确。 | R508 / 第 5 段 |
| `pricingScheme` | `"BQ"` | 全部采用 Bills of Quantities，明确不采用 Schedule of Rates，BQ 枚举值正确。 | R503 / 第 5 段 |
| `photocopyRateUpToA3` | `3.6` | 引文明示 up to A3 每张 HK$3.60；数值 3.6 正确，且不是尚未批准的 above A3 费率。 | R506 / 第 7 段 |
| `rseSubmissionsRequired` | `true` | 原文明示随标需 RSE endorsed submissions（包括临时开挖支护建议），true 正确。 | R510 / 第 5 段 |
| `roofingInstallationIncluded` | `true` | 原文明示包含屋面安装，true 正确。 | R514 / 第 5 段 |
| `roofingWarrantyRequired` | `true` | 原文另行明确屋面安装需要保修，true 正确。 | R514 / 第 5 段 |
| `refugeFloorInstallationIncluded` | `true` | 原文明示避难层安装 included，true 正确，虽其保修不需要。 | R514 / 第 5 段 |
| `refugeFloorWarrantyRequired` | `false` | 同一句明确 no refuge floor installation warranty，false 正确，未与包含安装混淆。 | R514 / 第 5 段 |
| `otherWaterproofingWarrantyRequired` | `true` | 第 11 文件明确 require other waterproofing installation warranty，并以九区域完整规格覆盖，true 正确；屋面及避难层决议分开。 | R511 / 第 5 段 |
| `acrylicPaintWarrantyRequired` | `false` | 明确 Do not require an acrylic paint warranty，false 正确。 | R514 / 第 6 段 |
| `wallTilingWarrantyRequired` | `false` | 同一句明确不要求 wall tiling warranty，false 正确。 | R514 / 第 6 段 |
| `playEquipmentIncluded` | `false` | 明确 Play equipment is excluded，false 正确。 | R514 / 第 6 段 |
| `iasmIncluded` | `true` | 明确包括 Impact Absorbing Surfacing Materials，位置为室内运动跑道，true 正确；不受 play equipment excluded 误导。 | R514 / 第 6 段 |
| `epoxyCastIronPipeWarrantyRequired` | `true` | 明确 Require the epoxy coated cast iron pipe installation warranty，true 正确。 | R514 / 第 6 段 |
| `latePossessionAdopted` | `true` | 明确 Adopt the optional late possession provision，true 正确。 | R512 / 第 9 段 |
| `additionalLossAdopted` | `false` | 明确 Do not adopt additional loss，false 正确；此条没有指定 Sections/relevant Section 的采用需求。 | R512 / 第 9 段 |
| `contractorLegalForm` | `"incorporatedJV"` | 原文明确 incorporated joint venture，映射 incorporatedJV 正确，不是未法人化合营或未定形式。 | R514 / 第 8 段 |
| `projectArchitectSalutation` | `"Ms"` | 原文明确 Use Ms as the salutation，Ms 正确，不是从姓名或性别推断。 | R513 / 第 5 段 |
| `specificationInspectionFloor` | `"6"` | Specification Library 查阅位置明确 floor 6 of Block U，6 正确，不是 drawings 的 2。 | R513 / 第 7 段 |
| `specificationInspectionBlock` | `"U"` | Specification Library 查阅位置明确 Block U，U 正确，不是 drawings 的 V。 | R513 / 第 7 段 |
| `drawingsInspectionFloor` | `"2"` | Drawings 查阅位置明确 floor 2 of Block V，2 正确，不是 Specification 的 6。 | R513 / 第 7 段 |
| `drawingsInspectionBlock` | `"V"` | Drawings 查阅位置明确 Block V，V 正确，不是 Specification 的 U。 | R513 / 第 7 段 |
| `siteInspectionStartDate` | `"2027-01-11"` | 原文 11 January 2027 标准化为 2027-01-11 正确；结束日期尚未确定，不能把它作为结束日期。 | R513 / 第 8 段 |

逐候选原文、段落或跨表格行定位及来源 SHA256 保存在同目录 `semantic-audit.json` 与 `native-source-projection.json`。
