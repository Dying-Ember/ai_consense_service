import { readFile, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'

const args = new Map()
for (let i = 2; i < process.argv.length; i++) {
  const name = process.argv[i]
  if (name === '--require-correct') args.set(name, true)
  else if (name.startsWith('--') && process.argv[i + 1]) args.set(name, process.argv[++i])
  else throw new Error(`Invalid argument: ${name}`)
}
for (const name of ['--trace', '--variables', '--gold']) if (!args.get(name)) throw new Error(`Required: ${name}`)
const texts = await Promise.all(['--trace', '--variables', '--gold'].map(name => readFile(args.get(name), 'utf8')))
const unwrap = value => value?.data ?? value
const [trace, variables, gold] = texts.map((value, i) => i === 2 ? JSON.parse(value.replace(/^\uFEFF/, '')) : unwrap(JSON.parse(value.replace(/^\uFEFF/, ''))))
if (!trace || !Array.isArray(variables) || !Array.isArray(gold.sourceCases)) throw new Error('Invalid trace, variables or expected-case document')

const decode = value => {
  if (typeof value !== 'string') return value
  if (!value.trim()) return null
  try { return JSON.parse(value) } catch { return value }
}
const unknown = value => value === null || value === undefined || typeof value === 'string' && (!value.trim() || value.trim().toLowerCase() === 'unknown')
const typography = value => String(value).replace(/[“”]/g, '"').replace(/[‘’]/g, "'").replace(/\\n/g, '\n').replace(/\s+/g, ' ').trim()
const groups = []
let legacyEscapedNewlineAdapter = false
if (Array.isArray(trace.parts) && trace.parts.length) {
  for (const part of trace.parts) {
    for (const attempt of part.attempts ?? []) groups.push({fileName:part.fileName, sourceText:part.sourceText, partId:part.partId, kind:attempt.kind, rawResponse:attempt.rawResponse})
  }
} else {
  const prompts = String(trace.userPrompt ?? '').split(/\r?\n\r?\n--- Evidence part ---\r?\n\r?\n/)
  if (prompts.length !== trace.rawResponses?.length) throw new Error('Legacy trace has ambiguous attempt/source mapping; use a trace with explicit parts')
  prompts.forEach((original, i) => {
    if (original.includes('\\n')) legacyEscapedNewlineAdapter = true
    const prompt = original.replace(/\\n/g, '\n')
    const match = /Source: ([^\n]+)\n([\s\S]*?)\nReturn a JSON array of supported allowed keys/.exec(prompt)
    if (!match) throw new Error(`Cannot locate full legacy source part ${i + 1}`)
    groups.push({fileName:match[1], sourceText:match[2], partId:`legacy-${i + 1}`, kind:'primary', rawResponse:trace.rawResponses[i]})
  })
}
const sourceText = new Map()
const rawObservations = []
const responseSyntax = []
for (const group of groups) {
  sourceText.set(group.fileName, (sourceText.get(group.fileName) ?? '') + '\n' + (group.sourceText ?? ''))
  try {
    const rows = JSON.parse(group.rawResponse)
    if (!Array.isArray(rows)) throw new Error('Top-level output is not an array')
    responseSyntax.push({partId:group.partId, kind:group.kind, valid:true, itemCount:rows.length})
    for (const row of rows) rawObservations.push({...row, fileName:group.fileName, partId:group.partId, attemptKind:group.kind, decoded:decode(row.value)})
  } catch (error) { responseSyntax.push({partId:group.partId, kind:group.kind, valid:false, error:error.message}) }
}
const intakeObservations = variables.flatMap(field => (field.candidates ?? []).map(candidate => ({...candidate, key:field.key, decoded:decode(candidate.value)})))
const prefills = variables.map(field => ({key:field.key, value:field.value, decoded:decode(field.value), adoptionState:field.adoptionState, confirmed:field.confirmed}))

const comparison = (key, value, expected) => {
  if (unknown(value)) return 'unanswered'
  if (key === 'contractTitle') {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return 'invalid_shape'
    return String(value.number ?? '') === expected.number && value.title === expected.title ? 'correct' : 'wrong_value'
  }
  if (key === 'billNos') {
    if (!Array.isArray(value) || value.some(row => !row || typeof row !== 'object' || Array.isArray(row))) return 'invalid_shape'
    const projected = value.map(row => ({number:String(row.number ?? ''), description:row.description}))
    return JSON.stringify(projected) === JSON.stringify(expected) ? 'correct' : 'wrong_value'
  }
  if (key === 'subcontractors') {
    if (!Array.isArray(value) || value.some(row => typeof row !== 'string')) return 'invalid_shape'
    return JSON.stringify([...value].sort()) === JSON.stringify([...expected].sort()) ? 'correct' : 'wrong_value'
  }
  return value === expected ? 'correct' : 'wrong_value'
}
for (const item of gold.sourceCases) {
  const coveredSources = [...sourceText.entries()].filter(([name]) => name.startsWith(item.sourcePrefix))
  if (!coveredSources.length) throw new Error(`Missing source coverage for expected case ${item.id}`)
  if (!coveredSources.some(([, text]) => typography(text).includes(typography(item.sourceQuote)))) throw new Error(`Independent source quote does not match case ${item.id}`)
  if (item.fact === 'billNos') {
    for (const row of gold.facts.billNos) {
      if (!coveredSources.some(([, text]) => typography(text).includes(typography(`${row.number} | ${row.description} |`)))) throw new Error(`Expected Bill ${row.number} is absent from the original source`)
    }
  }
}
const assess = (observations, cases, project = false) => cases.map(item => {
  const selected = observations.filter(row => (project || row.fileName?.startsWith(item.sourcePrefix)) && (item.key === '*' || row.key === item.key))
  const values = selected.filter(row => !unknown(row.decoded))
  const expected = item.expect === 'unknown' ? null : gold.facts[item.fact]
  const checks = values.map(row => ({key:row.key, value:row.decoded, result:expected === null ? 'unsupported_fill' : comparison(item.key, row.decoded, expected), confidence:row.confidence, partId:row.partId, attemptKind:row.attemptKind}))
  return {id:item.id, key:item.key, expected, correct:expected === null ? values.length === 0 : checks.some(row => row.result === 'correct'), returnedItemCount:selected.length, proposals:checks}
})
const metrics = cases => {
  const answerCases = cases.filter(row => row.expected !== null)
  const unknownCases = cases.filter(row => row.expected === null)
  return {caseCount:cases.length, supportedAnswerCases:answerCases.length, recoveredAnswerCases:answerCases.filter(row => row.correct).length,
    supportedAnswerRecall:answerCases.length ? answerCases.filter(row => row.correct).length / answerCases.length : null,
    unknownCases:unknownCases.length, correctAbstentions:unknownCases.filter(row => row.correct).length,
    wrongOrUnsupportedProposals:cases.reduce((count, row) => count + row.proposals.filter(value => ['wrong_value','unsupported_fill'].includes(value.result)).length, 0),
    invalidStructuredProposals:cases.reduce((count, row) => count + row.proposals.filter(value => value.result === 'invalid_shape').length, 0)}
}
const projectCases = Object.keys(gold.facts).map(key => ({id:`project-${key}`, key, fact:key, ...(gold.facts[key] === null ? {expect:'unknown'} : {})}))
const rawCases = assess(rawObservations, gold.sourceCases)
const acceptedCases = assess(intakeObservations, gold.sourceCases)
const formCases = assess(prefills, projectCases, true)
const report = {version:'drafting-harness-evaluation-20261006.3', generatedAt:new Date().toISOString(), model:trace.model, runId:trace.runId ?? null,
  traceFinishedAt:trace.finishedAt, goldVersion:gold.version, scope:gold.scope, unevaluated:gold.unevaluated,
  inputs:Object.fromEntries(['trace','variables','gold'].map((name, i) => [name,{path:args.get(`--${name}`), sha256:createHash('sha256').update(texts[i]).digest('hex')}])),
  sourceCount:sourceText.size, attemptCount:groups.length, legacyEscapedNewlineAdapter, responseSyntax,
  rawModel:{metrics:metrics(rawCases), cases:rawCases}, acceptedIntake:{metrics:metrics(acceptedCases), cases:acceptedCases}, projectPrefill:{metrics:metrics(formCases), cases:formCases},
  limitation:'Intake acceptance is not semantic certification. Recall is measured only against these independently written source cases; duplicated raw attempts and multiple source cases are not independent project fields. False, unknown and explicit [] differ. Bill metadata is unevaluated.'}
report.boundedQualityPassed = (!trace.status || trace.status === 'completed') && acceptedCases.every(row => row.correct) && formCases.every(row => row.correct && row.proposals.every(proposal => proposal.result === 'correct')) && acceptedCases.every(row => row.proposals.every(proposal => proposal.result === 'correct')) && responseSyntax.every(row => row.valid)
if (args.get('--output')) await writeFile(args.get('--output'), JSON.stringify(report, null, 2) + '\n', {encoding:'utf8', flag:'wx'})
process.stdout.write(JSON.stringify({sourceCount:report.sourceCount, attemptCount:report.attemptCount, rawModel:report.rawModel.metrics, acceptedIntake:report.acceptedIntake.metrics, projectPrefill:report.projectPrefill.metrics, boundedQualityPassed:report.boundedQualityPassed, output:args.get('--output') ?? null}, null, 2) + '\n')
if (args.get('--require-correct') && !report.boundedQualityPassed) process.exitCode = 3
