"""Summarize completed, source-bound timing events without invoking a service."""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import statistics


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--probe-run-dir',type=Path,required=True)
    parser.add_argument('--run-id',required=True)
    parser.add_argument('--project-id',required=True)
    parser.add_argument('--out',type=Path,required=True)
    args=parser.parse_args();args.out.mkdir(parents=True,exist_ok=False)
    groups={};artifacts=[];deferred=[]
    for path in sorted(args.probe_run_dir.glob('*-phase_timing.json')):
        raw=path.read_bytes()
        try:record=json.loads(raw)
        except (ValueError,UnicodeError):
            deferred.append({'path':str(path),'reason':'Not a complete JSON event at this snapshot'});continue
        if record['runId']!=args.run_id or record['projectId']!=args.project_id:
            raise RuntimeError('Mixed review identity: '+str(path))
        if record['phase']!='phase_timing':raise RuntimeError('Unexpected timing phase')
        observation=record['observations']
        nanos=observation['wallNanos']
        if not isinstance(nanos,int) or nanos<0:raise RuntimeError('Invalid observed elapsed')
        groups.setdefault(observation['stage'],[]).append(nanos/1e9)
        artifacts.append({'path':str(path),'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest(),
                          'callId':record['callId'],'topicIndex':record['topicIndex'],'role':record['role']})
    summary={stage:{'completedTimingCount':len(values),'sumObservedSeconds':sum(values),
                    'minimumSeconds':min(values),'medianSeconds':statistics.median(values),
                    'nearestRankP95Seconds':sorted(values)[max(0,math.ceil(.95*len(values))-1)],
                    'maximumSeconds':max(values)}for stage,values in groups.items()}
    overhead=None
    overhead_path=args.probe_run_dir/'probe_overhead_manifest.json'
    if overhead_path.exists():
        raw=overhead_path.read_bytes();overhead=json.loads(raw)
        if overhead['runId']!=args.run_id or overhead['projectId']!=args.project_id:
            raise RuntimeError('Overhead identity differs')
        artifacts.append({'path':str(overhead_path),'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()})
    result={'schemaVersion':1,'observedAtUtc':datetime.now(timezone.utc).isoformat(),'runId':args.run_id,
            'projectId':args.project_id,'snapshotScope':'Completed timing JSON events only; not a completion assertion',
            'stageTimingSummary':summary,'overheadManifest':overhead,'deferredEvents':deferred,
            'inputArtifacts':artifacts,'softwareSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            'modelHttpGpuOcrDbCalls':0,'semanticQualityAccepted':None,
            'limits':['Nested stages overlap and must not be added into an overall duration.',
                      'A completed timing event does not establish a completed topic, successful gate, or correct interpretation.',
                      'Java stage wall excludes measured event I/O but excludes no unmeasured caller observation construction.',
                      'Incomplete phases have no invented elapsed. Final summary flush is excluded from its own overhead bytes.']}
    with (args.out/'java_probe_timing_summary.json').open('x',encoding='utf-8',newline='\n')as stream:
        json.dump(result,stream,ensure_ascii=False,indent=2,allow_nan=False);stream.write('\n')
    print(json.dumps({'receipt':str(args.out/'java_probe_timing_summary.json'),'completedTimingEvents':len(artifacts),
                      'stages':summary,'overheadSummaryPresent':overhead is not None}))


if __name__=='__main__':main()
