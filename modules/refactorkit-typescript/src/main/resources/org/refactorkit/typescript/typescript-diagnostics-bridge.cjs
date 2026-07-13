'use strict';

const path = require('path');

function fail(message) {
  process.stdout.write(JSON.stringify({ schema: 1, complete: false, failure: String(message), diagnostics: [] }));
  process.exitCode = 0;
}

try {
  const [compilerPath, root, snapshotHash, ...configs] = process.argv.slice(2);
  if (!compilerPath || !root || !snapshotHash || configs.length === 0) {
    fail('missing compiler, root, snapshot hash, or project config');
  } else {
    const ts = require(compilerPath);
    const normalizedRoot = path.resolve(root);
    const compilerRoot = path.dirname(path.resolve(compilerPath));
    const allowed = candidate => {
      const absolute = path.resolve(candidate);
      return absolute === normalizedRoot || absolute.startsWith(normalizedRoot + path.sep) ||
        absolute === compilerRoot || absolute.startsWith(compilerRoot + path.sep);
    };
    const safeSystem = {
      ...ts.sys,
      fileExists: candidate => allowed(candidate) && ts.sys.fileExists(candidate),
      readFile: (candidate, encoding) => allowed(candidate) ? ts.sys.readFile(candidate, encoding) : undefined,
      directoryExists: candidate => allowed(candidate) && !!ts.sys.directoryExists?.(candidate),
      getDirectories: candidate => allowed(candidate) ? (ts.sys.getDirectories?.(candidate) || []).filter(allowed) : [],
      readDirectory: (candidate, extensions, excludes, includes, depth) => allowed(candidate)
        ? ts.sys.readDirectory(candidate, extensions, excludes, includes, depth).filter(allowed) : [],
      realpath: candidate => allowed(candidate) && ts.sys.realpath ? ts.sys.realpath(candidate) : path.resolve(candidate),
    };
    const diagnostics = [];
    for (const relativeConfig of configs) {
      const configPath = path.resolve(root, relativeConfig);
      const loaded = ts.readConfigFile(configPath, safeSystem.readFile);
      if (loaded.error) {
        diagnostics.push(loaded.error);
        continue;
      }
      const parsed = ts.parseJsonConfigFileContent(loaded.config, safeSystem, path.dirname(configPath), {
        noEmit: true,
        incremental: false,
        tsBuildInfoFile: undefined,
      }, configPath);
      diagnostics.push(...parsed.errors);
      if (parsed.fileNames.length === 0) continue;
      const options = { ...parsed.options, noEmit: true, incremental: false, tsBuildInfoFile: undefined };
      const host = ts.createCompilerHost(options, true);
      host.fileExists = safeSystem.fileExists;
      host.readFile = safeSystem.readFile;
      host.directoryExists = safeSystem.directoryExists;
      host.getDirectories = safeSystem.getDirectories;
      host.realpath = safeSystem.realpath;
      const program = ts.createProgram({
        rootNames: parsed.fileNames,
        options,
        projectReferences: parsed.projectReferences,
        host,
      });
      diagnostics.push(...ts.getPreEmitDiagnostics(program));
      if (diagnostics.length > 500) break;
    }
    if (diagnostics.length > 500) {
      process.stdout.write(JSON.stringify({ schema: 1, complete: false, failure: 'diagnostic limit exceeded', diagnostics: [] }));
    } else {
      const output = diagnostics.map(diagnostic => {
        let file = null;
        let line = null;
        let character = null;
        let endLine = null;
        let endCharacter = null;
        if (diagnostic.file && Number.isInteger(diagnostic.start)) {
          const absolute = path.resolve(diagnostic.file.fileName);
          const relative = path.relative(normalizedRoot, absolute);
          if (relative && !relative.startsWith('..') && !path.isAbsolute(relative)) {
            file = relative.split(path.sep).join('/');
            const start = diagnostic.file.getLineAndCharacterOfPosition(diagnostic.start);
            const end = diagnostic.file.getLineAndCharacterOfPosition(diagnostic.start + (diagnostic.length || 0));
            line = start.line;
            character = start.character;
            endLine = end.line;
            endCharacter = end.character;
          }
        }
        return {
          code: `TS${diagnostic.code}`,
          category: diagnostic.category,
          message: ts.flattenDiagnosticMessageText(diagnostic.messageText, '\n'),
          file, line, character, endLine, endCharacter,
        };
      });
      process.stdout.write(JSON.stringify({ schema: 1, complete: true, snapshotHash, diagnostics: output }));
    }
  }
} catch (error) {
  fail(error && error.stack ? error.stack : error);
}
