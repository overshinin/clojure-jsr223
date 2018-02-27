## CLOJURE-JSR223

This is an implementation of JSR 223 (Java Scripting API) for Clojure.

Requires Java 5+, Clojure 1.5.1+ (the latest version, which was compiled using Java 5)

#### To build
- create `lib` directory and put https://repo1.maven.org/maven2/org/clojure/clojure/1.9.0/clojure-1.9.0.jar there,
  or https://repo1.maven.org/maven2/org/clojure/clojure/1.5.1/clojure-1.5.1.jar + `script-api.jar` from https://www.jcp.org/en/jsr/detail?id=223 (to build for Java 5)
- optionally set environment variable(s) (`$JAVA_HOME_5`, `$JAVA_HOME_6`, `$JAVA_HOME_7`)
- use `ant`

#### To test
- put http://central.maven.org/maven2/org/clojure/core.specs.alpha/0.1.24/core.specs.alpha-0.1.24.jar and http://central.maven.org/maven2/org/clojure/spec.alpha/0.1.143/spec.alpha-0.1.143.jar into `lib` directory
  or use `ant` target `runtest5`
- use `ant`
