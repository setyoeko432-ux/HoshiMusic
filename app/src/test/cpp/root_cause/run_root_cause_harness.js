#!/usr/bin/env node
/**
 * Dedicated Native DSP Root Cause Diagnostic Runner Script.
 *
 * Compiles DspRootCauseHarness.cpp using Android NDK clang++ (WASM target)
 * and executes the diagnostic harness suite via Node.js WebAssembly runtime.
 * Generates JSON and CSV diagnostic reports for root cause analysis.
 */

'use strict';

const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
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
  cut.push('// [root-cause harness] JNI block removed');
  cut.push('');
  return cut.join('\n');
}

function build() {
  const ndkBin = findNdkBin();
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'hosi-rc-'));

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

    const wasm = path.join(work, 'hoshi_rc_harness.wasm');
    const args = BUILD_FLAGS.split(' ').concat([
      '-I', work,
      '-I', SHIM_DIR,
      '-I', path.join(SHIM_DIR, 'android'),
      '-Wl,--no-entry',
      '-Wl,--export-memory',
      '-Wl,-z,stack-size=1048576',
      '-o', wasm,
      trimmed,
      path.join(ROOT_CAUSE_DIR, 'DspRootCauseHarness.cpp'),
      path.join(SHIM_DIR, 'hosi_rt.cpp'),
    ]);

    const env = Object.assign({}, process.env);
    env.PATH = shimBin + ';' + env.PATH;

    try {
      execFileSync(path.join(ndkBin, 'clang++.exe'), args, { stdio: 'pipe', env });
    } catch (e) {
      const out = (e.stdout ? e.stdout.toString() : '') + (e.stderr ? e.stderr.toString() : '');
      fail('BUILD ROOT CAUSE HARNESS GAGAL:\n' + out);
    }

    const compiledWasm = fs.readFileSync(wasm);
    return compiledWasm;
  } finally {
    fs.rmSync(work, { recursive: true, force: true });
  }
}

function run(wasmBytes) {
  const mod = new WebAssembly.Module(wasmBytes);
  const instance = new WebAssembly.Instance(mod, {});
  const ex = instance.exports;

  ex['hosiRootCauseRun']();

  const reportLen = ex['hosiReportLen']();
  const reportPtr = ex['hosiReportPtr']();
  const report = Buffer.from(ex['memory'].buffer, reportPtr, reportLen).toString('utf8');

  return report;
}

function parseAndExportReports(rawReport) {
  fs.mkdirSync(REPORT_OUTPUT_DIR, { recursive: true });

  const lines = rawReport.split('\n');
  const results = {
    jniBufferOffset: [],
    masterEnable: [],
    agcInterference: [],
    transientSweep: [],
    bufferConvergence: [],
    sensitivityMatrix: [],
    presetDifferentiation: [],
  };

  for (const line of lines) {
    if (line.startsWith('RC_RESULT|A_1_OFFSET_ZERO_SENTINEL_PREFIX=')) {
      results.jniBufferOffset.push(line);
    } else if (line.startsWith('RC_RESULT|B_1_PASSTHROUGH_OFF_DIFF_RMS=')) {
      results.masterEnable.push(line);
    } else if (line.startsWith('RC_RESULT|D_AGC|')) {
      results.agcInterference.push(line);
    } else if (line.startsWith('RC_RESULT|E_TRANSIENT_SWEEP|')) {
      results.transientSweep.push(line);
    } else if (line.startsWith('RC_RESULT|F_BLOCK_SIZE|')) {
      results.bufferConvergence.push(line);
    } else if (line.startsWith('RC_RESULT|G_SENSITIVITY|')) {
      results.sensitivityMatrix.push(line);
    } else if (line.startsWith('RC_RESULT|H_PRESET|')) {
      results.presetDifferentiation.push(line);
    }
  }

  // Export JSON Report
  const jsonReportPath = path.join(REPORT_OUTPUT_DIR, 'root-cause-report.json');
  fs.writeFileSync(jsonReportPath, JSON.stringify(results, null, 2), 'utf8');

  // Export CSV Files
  const csvJni = 'Test,PrefixSentinel,SuffixSentinel,PcmModified\n' +
    results.jniBufferOffset.map(l => l.replace('RC_RESULT|A_1_OFFSET_ZERO_SENTINEL_PREFIX=', '')).join('\n') + '\n';
  fs.writeFileSync(path.join(REPORT_OUTPUT_DIR, 'jni-buffer-validation.csv'), csvJni, 'utf8');

  const csvAgc = 'Preset,InputDb,InputRms,OutputRms,GainDb,AgcPreGain,SlowRms\n' +
    results.agcInterference.map(l => l.replace('RC_RESULT|D_AGC|preset=', '').replaceAll('|inDb=', ',').replaceAll('|inRms=', ',').replaceAll('|outRms=', ',').replaceAll('|gainDb=', ',').replaceAll('|agcPreGain=', ',').replaceAll('|slowRms=', ',')).join('\n') + '\n';
  fs.writeFileSync(path.join(REPORT_OUTPUT_DIR, 'agc-interference.csv'), csvAgc, 'utf8');

  const csvConv = 'BlockSize,DurationSec,CurAirBoostDb,CurMudCutDb,VocalEnergy\n' +
    results.bufferConvergence.map(l => l.replace('RC_RESULT|F_BLOCK_SIZE|bs=', '').replaceAll('|durSec=', ',').replaceAll('|curAirBoostDb=', ',').replaceAll('|curMudCutDb=', ',').replaceAll('|vocalEnergy=', ',')).join('\n') + '\n';
  fs.writeFileSync(path.join(REPORT_OUTPUT_DIR, 'buffer-convergence.csv'), csvConv, 'utf8');

  const csvSens = 'Index,Name,Value,RmsDb,Peak\n' +
    results.sensitivityMatrix.map(l => l.replace('RC_RESULT|G_SENSITIVITY|idx=', '').replaceAll('|name=', ',').replaceAll('|val=', ',').replaceAll('|rmsDb=', ',').replaceAll('|peak=', ',')).join('\n') + '\n';
  fs.writeFileSync(path.join(REPORT_OUTPUT_DIR, 'parameter-sensitivity.csv'), csvSens, 'utf8');

  const csvPresets = 'PresetId,OutputRmsDb,DeltaVsRefDb,AdspWet,AirBoostDb,MudCutDb\n' +
    results.presetDifferentiation.map(l => l.replace('RC_RESULT|H_PRESET|id=', '').replaceAll('|outRmsDb=', ',').replaceAll('|deltaVsRefDb=', ',').replaceAll('|adspWet=', ',').replaceAll('|airDb=', ',').replaceAll('|mudDb=', ',')).join('\n') + '\n';
  fs.writeFileSync(path.join(REPORT_OUTPUT_DIR, 'preset-differentiation.csv'), csvPresets, 'utf8');

  log('Reports successfully written to: ' + REPORT_OUTPUT_DIR);
}

function main() {
  log('Starting Native DSP Root Cause Diagnostic Suite Execution...');
  const wasm = build();
  const rawReport = run(wasm);
  console.log(rawReport);
  parseAndExportReports(rawReport);
}

main();
