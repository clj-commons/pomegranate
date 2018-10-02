## [Pomegranate](http://github.com/cemerick/pomegranate) changelog

### [`1.1.0`](https://github.com/cemerick/pomegranate/milestone/9?closed=1)

* Upgrade Maven Resolver dependencies (gh-103)

### [`1.0.0`](https://github.com/cemerick/pomegranate/milestone/8?closed=1)

* Ensures JDK 9 compatibility via dynapath 1.0.0; a non-trivial breaking change
  given the effect of the change there to avoid JDK 9's warnings re: reflective
  calls into non-public methods, etc (`URLClassLoader`s are no longer modifiable
  by default) (gh-92)

### [`0.4.0`](https://github.com/cemerick/pomegranate/issues?q=milestone%3A0.4.0+is%3Aclosed)

* Non-TLS HTTP repositories are unsupported; using a "bare" HTTP repository now
  requires registering a "wagon" for the insecure transport method (gh-83)
* Switched/upgraded from Sonatype Aether to Maven-resolver (gh-80)
* Upgraded to Maven `3.5.0` (gh-83)
* `add-dependencies` now allows you to specify which `ClassLoader` to modify
  (gh-63)
* Repository names can now be any `Named` value (strings, keywords, or symbols)
  (gh-84)
* Some previously-internal functions are now marked as public:
  `merge-versions-from-managed-coords` (gh-74), and `dependency` and
  `make-repository` (gh-69)

###
[`0.3.1`](https://github.com/cemerick/pomegranate/issues?q=milestone%3A0.3.1+is%3Aclosed)

* Add support for "managed" dependencies in `resolve-dependencies` (gh-72)

###
[`0.3.0`](https://github.com/cemerick/pomegranate/issues?milestone=5&page=1&state=closed)

* Added `cemerick.pomegranate.aether/resolve-artifacts`, which allows for the
  resolution of a sequence of artifacts, without doing transitive resolution on
  their dependencies. (gh-57)
* Provide an error if a registered wagon factory fails to instantiate (gh-58)

### `0.2.0`

_This release contains breaking changes from `0.1.3`_, though it's expected that
the affected APIs are rarely used.

**Dynamic classpath support (`cemerick.pomegranate`)**

* The `URLClasspath` protocol has been removed in favor of using
  [dynapath](https://github.com/tobias/dynapath/)'s `DynamicClasspath` protocol.
  (gh-43) *breaking change*
* `classloader-hierarchy` now starts at the current thread context classloader
  instead of Clojure's "baseLoader". *breaking change*
* New `resources` and `classloader-resources` fns for determining from which
  classloader(s) a given resource is available (gh-48)

**Aether API (`cemerick.pomegranate.aether` and friends)**

* `install-artifacts` and `deploy-artifacts` are now generalized to operate over
  multiple files (vs. the prior assumptions re: an artifact + a POM) (gh-52)
* `resolve-dependencies*` now available that returns the bare Aether results of
  dependency resolution, _sans_ Clojure-friendly graphification (gh-50)
* `resolve-dependencies`, `install-artifacts`, and `deploy-artifacts` now accept
  an optional `:repository-session-fn` to potentially modify the Aether
  `RespositorySystemSession` prior to its use (gh-56)
  
