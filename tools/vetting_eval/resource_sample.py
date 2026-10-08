"""Optional observational resource sampler; never calls OCR, retrieval or LLM APIs."""
from datetime import datetime, timezone
import csv
import io
import json
from pathlib import Path
import subprocess
import threading
import time


class ResourceSampler:
    def __init__(self, output: Path, note: str, interval=1.0):
        import psutil
        self.psutil = psutil
        self.output, self.note, self.interval = output, note, interval
        self.phase = 'source_validation'
        self.stop_event = threading.Event()
        self.thread = None
        self.started = None
        self.count = 0
        self.gpu_peak = {}
        self.process_peak = {}
        self.machine_used_peak = 0
        self.machine_available_min = None
        self.errors = []
        self.last_sample = None
        self.gaps = []

    def start(self):
        if self.thread is not None:
            return
        self.started = time.monotonic()
        self.thread = threading.Thread(target=self._run, name='vetting-resource-sampler', daemon=True)
        self.thread.start()

    def set_phase(self, phase):
        self.phase = phase

    def _sample(self):
        now = time.monotonic()
        if self.last_sample is not None:
            self.gaps.append(now-self.last_sample)
        self.last_sample = now
        memory = self.psutil.virtual_memory()
        row = {'timestamp': datetime.now(timezone.utc).isoformat(), 'elapsedSeconds': now-self.started,
               'phase': self.phase, 'machineRam': {'totalBytes': memory.total,
                   'usedBytes': memory.used, 'availableBytes': memory.available, 'usedPercent': memory.percent},
               'processes': [], 'gpu': []}
        self.machine_used_peak = max(self.machine_used_peak, memory.used)
        self.machine_available_min = min(self.machine_available_min or memory.available, memory.available)
        for process in self.psutil.process_iter(['pid', 'name', 'memory_info']):
            info = process.info
            name = (info['name'] or '').lower()
            if name not in ('java.exe', 'java', 'python.exe', 'python', 'pythonw.exe', 'ollama.exe', 'ollama'):
                continue
            if info['memory_info'] is None:
                continue
            record = {'pid': info['pid'], 'name': info['name'], 'rssBytes': info['memory_info'].rss}
            row['processes'].append(record)
            peak = self.process_peak.get(str(info['pid']))
            if peak is None or peak['rssBytes'] < record['rssBytes']:
                self.process_peak[str(info['pid'])] = record
        try:
            result = subprocess.run(['nvidia-smi', '--query-gpu=index,name,memory.used,memory.total,utilization.gpu',
                                     '--format=csv,noheader,nounits'], capture_output=True, text=True,
                                    timeout=3, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0), check=True)
            for values in csv.reader(io.StringIO(result.stdout)):
                index, name, used, total, utilization = (v.strip() for v in values)
                gpu = {'index': int(index), 'name': name, 'memoryUsedMiB': int(used),
                       'memoryTotalMiB': int(total), 'utilizationPercent': int(utilization)}
                row['gpu'].append(gpu)
                peak = self.gpu_peak.get(index)
                if peak is None or peak['memoryUsedMiB'] < gpu['memoryUsedMiB']:
                    self.gpu_peak[index] = gpu
        except Exception as error:
            row['gpuError'] = f'{type(error).__name__}: {error}'
            if row['gpuError'] not in self.errors:
                self.errors.append(row['gpuError'])
        self.count += 1
        return row

    def _run(self):
        with (self.output/'resource_samples.jsonl').open('w', encoding='utf-8') as stream:
            while not self.stop_event.is_set():
                before = time.monotonic()
                try:
                    row = self._sample()
                except Exception as error:
                    row = {'timestamp': datetime.now(timezone.utc).isoformat(), 'phase': self.phase,
                           'samplingError': f'{type(error).__name__}: {error}'}
                    self.errors.append(row['samplingError'])
                stream.write(json.dumps(row, ensure_ascii=False)+'\n')
                stream.flush()
                self.stop_event.wait(max(0, self.interval-(time.monotonic()-before)))

    def stop(self):
        if self.thread is None:
            return None
        self.stop_event.set()
        self.thread.join(timeout=5)
        result = {'samples': self.count, 'requestedIntervalSeconds': self.interval,
                  'elapsedSeconds': time.monotonic()-self.started,
                  'sampleGapSeconds': {'minimum': min(self.gaps) if self.gaps else None,
                                       'maximum': max(self.gaps) if self.gaps else None},
                  'sampledGpuMemoryPeaks': list(self.gpu_peak.values()),
                  'sampledProcessRssPeaks': sorted(self.process_peak.values(), key=lambda row: row['pid']),
                  'sampledMachineRamUsedPeakBytes': self.machine_used_peak,
                  'sampledMachineRamAvailableMinimumBytes': self.machine_available_min,
                  'errors': self.errors, 'note': self.note,
                  'method': 'One background thread samples nvidia-smi and existing psutil every requested second; actual intervals are recorded. All matching Java/Python/Ollama process RSS and whole-machine/GPU usage include other work. Sampled peaks can miss shorter spikes and are not absolute peaks. No model API is called.'}
        (self.output/'resource_summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        return result
