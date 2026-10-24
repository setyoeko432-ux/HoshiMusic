#!/usr/bin/env node
/**
 * Real-time DSP Crackling & Bluetooth TWS Diagnostic Runner Script.
 *
 * Compiles DspRealtimeArtifactHarness.cpp via NDK clang++ (WASM target)
 * and executes real-time processing deadline, PCM clipping, and Bluetooth route tests.
 */

'use strict';

const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');

const ROOT_CAUSE_DIR = __dirname;
const CPP_DIR = path.resolve(ROOT_CAUSE_DIR, '..');
const MAIN_CPP = path.resolve(CPP_DIR, '..', '..', 'main', 'cpp');
const CORE_CPP = path.join(MAIN_CPP, 'hoshi_dsp_core.cpp');
const SHIM_DIR = path.join(CPP_DIR, 'shim');
const REPORT_OUTPUT_DIR = path.join(ROOT_CAUSE_DIR, 'reports');

const BUILD_FLAGS = [
  '--target=wasm32-unknown-unknown',
  '-std=c++17',
  '-O2',
  '-fno-exceptions',
  '-fno-rtti',
  '-fno-fast-math',
  '-ffp-contract=off',
  '-nostdlib',
  '-nostdlib++',
  '-mbulk-memory',
].join(' ');

function log(msg) { console.log(msg); }
function fail(msg, code) {
  console.error('ERROR: ' + msg);
  process.exit(code === undefined ? 1 : code);
}

function findNdkBin() {
  const candidates = [];
  if (process.env.ANDROID_NDK_HOME) candidates.push(path.join(process.env.ANDROID_NDK_HOME, 'toolchains', 'llvm', 'prebuilt', 'windows-x86_64', 'bin'));
  if (process.env.NDK_HOME) candidates.push(path.join(process.env.NDK_HOME, 'toolchains', 'llvm', 'prebuilt', 'windows-x86_64', 'bin'));
  if (process.env.LOCALAPPDATA) candidates.push(path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'ndk'));

  const parseSemver = (v) => v.split('.').map((p) => parseInt(p, 10) || 0);
  const compareSemver = (a, b) => {
    const pa = parseSemver(a), pb = parseSemver(b);
    const len = Math.max(pa.length, pb.length);
    for (let i = 0; i < len; i++) {
      const diff = (pb[i] || 0) - (pa[i] || 0);
      if (diff !== 0) return diff;
    }
    return 0;
  };

  const hostOs = os.platform() === 'win32' ? 'windows-x86_64' : (os.platform() === 'darwin' ? 'darwin-x86_64' : 'linux-x86_64');
  const compilerName = os.platform() === 'win32' ? 'clang++.exe' : 'clang++';

  for (const root of candidates) {
    if (!fs.existsSync(root)) continue;
    let versions;
    try {
      versions = fs.readdirSync(root).filter((v) => /^\d/.test(v)).sort(compareSemver);
    } catch (e) { continue; }
    for (const v of versions) {
      const bin = path.join(root, v, 'toolchains', 'llvm', 'prebuilt', hostOs, 'bin');
      if (fs.existsSync(path.join(bin, compilerName))) return bin;
    }
  }
  fail('clang++ NDK tidak ditemukan. Set ANDROID_NDK_HOME.', 1);
}

function trimSource(source) {
  const lines = source.split(/\r?\n/);
  const idx = lines.findIndex((l) => l.trim() === 'extern "C" {');
  if (idx < 0) fail('Penanda JNI `extern "C" {` tidak ditemukan di hoshi_dsp_core.cpp');
  const cut = lines.slice(0, idx);
  cut.push('');
  cut.push('// [realtime artifact harness] JNI block removed');
  cut.push('');
  return cut.join('\n');
}

function build() {
  const ndkBin = findNdkBin();
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'hosi-rt-art-'));

  try {
    const shimBin = path.join(work, 'bin');
    fs.mkdirSync(shimBin, { recursive: true });

    fs.copyFileSync(path.join(ndkBin, 'ld.lld.exe'), path.join(shimBin, 'wasm-ld.exe'));
    for (const dll of fs.readdirSync(ndkBin)) {
      if (dll.endsWith('.dll')) fs.copyFileSync(path.join(ndkBin, dll), path.join(shimBin, dll));
    }

    const headers = fs.readdirSync(MAIN_CPP).filter((f) => f.endsWith('.h'));
    for (const hname of headers) fs.copyFileSync(path.join(MAIN_CPP, hname), path.join(work, hname));

    const trimmed = path.join(work, 'hoshi_dsp_core_nojni.cpp');
    fs.writeFileSync(trimmed, trimSource(fs.readFileSync(CORE_CPP, 'utf8')), 'utf8');

    const wasm = path.join(work, 'hoshi_rt_artifact_harness.wasm');
    const args = BUILD_FLAGS.split(' ').concat([
      '-I', work,
      '-I', SHIM_DIR,
      '-I', path.join(SHIM_DIR, 'android'),
      '-Wl,--no-entry',
      '-Wl,--export-memory',
      '-Wl,-z,stack-size=1048576',
      '-o', wasm,
      trimmed,
      path.join(ROOT_CAUSE_DIR, 'DspRealtimeArtifactHarness.cpp'),
      path.join(SHIM_DIR, 'hosi_rt.cpp'),
    ]);

    const env = Object.assign({}, process.env);
    env.PATH = shimBin + ';' + env.PATH;

    try {
      execFileSync(path.join(ndkBin, 'clang++.exe'), args, { stdio: 'pipe', env });
    } catch (e) {
      const out = (e.stdout ? e.stdout.toString() : '') + (e.stderr ? e.stderr.toString() : '');
      fail('BUILD REALTIME ARTIFACT HARNESS GAGAL:\n' + out);
    }

    return fs.readFileSync(wasm);
  } finally {
    fs.rmSync(work, { recursive: true, force: true });
  }
}

function run(wasmBytes) {
  const mod = new WebAssembly.Module(wasmBytes);
  const instance = new WebAssembly.Instance(mod, {});
  const ex = instance.exports;

  ex['hosiRealtimeArtifactRun']();

  const reportLen = ex['hosiReportLen']();
  const reportPtr = ex['hosiReportPtr']();
  return Buffer.from(ex['memory'].buffer, reportPtr, reportLen).toString('utf8');
}

function parseAndExportReports(rawReport) {
  fs.mkdirSync(REPORT_OUTPUT_DIR, { recursive: true });

  const lines = rawReport.split('\n');
  const results = {
    timing: [],
    pcmIntegrity: [],
    bluetoothRoute: [],
  };

  for (const line of lines) {
    if (line.startsWith('RT_RESULT|TIMING|')) results.timing.push(line);
    else if (line.startsWith('RC_RESULT|PCM_INTEGRITY|')) results.pcmIntegrity.push(line);
    else if (line.startsWith('RC_RESULT|BT_ROUTE|')) results.bluetoothRoute.push(line);
  }

  // Export JSON Report
  const jsonReportPath = path.join(REPORT_OUTPUT_DIR, 'realtime_artifact_report.json');
  fs.writeFileSync(jsonReportPath, JSON.stringify(results, null, 2), 'utf8');

  // Export CSV Reports
  const csvTiming = 'SampleRate,BlockSize,DeadlineUs,Violations,Status\n' +
    results.timing.map(l => l.replace('RT_RESULT|TIMING|sr=', '').replaceAll('|bs=', ',').replaceAll('|deadlineUs=', ',').replaceAll('|violations=', ',').replaceAll('|status=', ',')).join('\n') + '\n';
  fs.writeFileSync(path.join(REPORT_OUTPUT_DIR, 'realtime_timing.csv'), csvTiming, 'utf8');

  const csvPcm = 'PresetId,NanCount,InfCount,OutOfBoundsCount,ClipEvents,Peak,DcOffsetL,Status\n' +
    results.pcmIntegrity.map(l => l.replace('RC_RESULT|PCM_INTEGRITY|preset=', '').replaceAll('|nan=', ',').replaceAll('|inf=', ',').replaceAll('|outOfBounds=', ',').replaceAll('|clipEvents=', ',').replaceAll('|peak=', ',').replaceAll('|dcOffsetL=', ',').replaceAll('|status=', ',')).join('\n') + '\n';
  fs.writeFileSync(path.join(REPORT_OUTPUT_DIR, 'pcm_integrity.csv'), csvPcm, 'utf8');

  const csvBt = 'RouteName,InitStatus,SampleRate,AdspWet\n' +
    results.bluetoothRoute.map(l => l.replace('RC_RESULT|BT_ROUTE|route=', '').replaceAll('|initOk=', ',').replaceAll('|sampleRate=', ',').replaceAll('|adspWet=', ',')).join('\n') + '\n';
  fs.writeFileSync(path.join(REPORT_OUTPUT_DIR, 'bluetooth_route_report.csv'), csvBt, 'utf8');

  log('Realtime artifact reports written to: ' + REPORT_OUTPUT_DIR);
}

function main() {
  log('Starting Real-Time DSP Crackling & Bluetooth TWS Diagnostic...');
  const wasm = build();
  const rawReport = run(wasm);
  console.log(rawReport);
  parseAndExportReports(rawReport);
}

main();
