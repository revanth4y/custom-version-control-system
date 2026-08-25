import { describe, expect, it } from "vitest";

import { SUPPORTED_LANGUAGES, languageFor } from "./language";

describe("choosing a grammar", () => {
  it("resolves the languages GitForge already claims to know", () => {
    expect(languageFor("src/Engine.java")).toBe("java");
    expect(languageFor("app.js")).toBe("javascript");
    expect(languageFor("component.tsx")).toBe("typescript");
    expect(languageFor("main.py")).toBe("python");
    expect(languageFor("style.css")).toBe("css");
    expect(languageFor("main.go")).toBe("go");
    expect(languageFor("lib.rs")).toBe("rust");
    expect(languageFor("script.sh")).toBe("bash");
    expect(languageFor("query.sql")).toBe("sql");
    expect(languageFor("config.yaml")).toBe("yaml");
  });

  it("maps related extensions onto one grammar", () => {
    expect(languageFor("a.jsx")).toBe("javascript");
    expect(languageFor("a.mjs")).toBe("javascript");
    expect(languageFor("page.html")).toBe("xml");
    expect(languageFor("icon.svg")).toBe("xml");
    expect(languageFor("a.cc")).toBe("cpp");
    expect(languageFor("a.hpp")).toBe("cpp");
  });

  it("ignores case in the extension", () => {
    expect(languageFor("README.MD")).toBe("markdown");
    expect(languageFor("Main.JAVA")).toBe("java");
  });

  it("reads the filename when there is no useful extension", () => {
    expect(languageFor("Dockerfile")).toBe("dockerfile");
    expect(languageFor("build/Dockerfile.dev")).toBe("dockerfile");
  });

  it("uses only the last path segment", () => {
    expect(languageFor("java/src/notes.md")).toBe("markdown");
    expect(languageFor("python/app.go")).toBe("go");
  });

  describe("when there is no grammar", () => {
    it("says so for an unknown extension rather than guessing", () => {
      expect(languageFor("data.xyz")).toBeNull();
      expect(languageFor("archive.tar")).toBeNull();
    });

    it("says so for a file with no extension", () => {
      expect(languageFor("CHANGELOG")).toBeNull();
      expect(languageFor("LICENSE")).toBeNull();
      expect(languageFor("Makefile")).toBeNull();
    });

    it("never highlights a binary file", () => {
      expect(languageFor("logo.png", { binary: true })).toBeNull();
      expect(languageFor("app.js", { binary: true })).toBeNull();
    });

    it("handles an absent or empty path", () => {
      expect(languageFor("")).toBeNull();
      expect(languageFor(null)).toBeNull();
      expect(languageFor(undefined)).toBeNull();
      expect(languageFor("dir/")).toBeNull();
    });
  });

  describe("the closed set", () => {
    it("lists every grammar the build can load", () => {
      expect(SUPPORTED_LANGUAGES.length).toBeGreaterThan(15);
      expect(SUPPORTED_LANGUAGES).toContain("java");
      expect(SUPPORTED_LANGUAGES).toContain("dockerfile");
    });

    it("contains no duplicates and no plaintext placeholder", () => {
      expect(new Set(SUPPORTED_LANGUAGES).size).toBe(SUPPORTED_LANGUAGES.length);
      expect(SUPPORTED_LANGUAGES).not.toContain("plaintext");
    });

    it("only ever returns a grammar from that set", () => {
      const paths = ["a.java", "a.js", "a.ts", "a.py", "a.html", "a.css", "a.c", "a.cpp",
                     "a.cs", "a.go", "a.rs", "a.php", "a.rb", "a.sh", "a.sql", "a.yml",
                     "a.json", "a.md", "a.kt", "a.swift", "a.diff", "Dockerfile"];
      for (const p of paths) {
        expect(SUPPORTED_LANGUAGES).toContain(languageFor(p));
      }
    });
  });
});
