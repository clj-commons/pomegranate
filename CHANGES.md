## [Pomegranate](http://github.com/cemerick/pomegranate) changelog

### `0.2.0`

_This release contains breaking changes from `0.1.3`_, though it's expected
that the affected APIs are rarely used.

**Dynamic classpath support (`cemerick.pomegranate`)**

* The `URLClasspath` protocol has been removed in favor of using
  [dynapath](https://github.com/tobias/dynapath/)'s `DynamicClasspath`
protocol. (gh-43) *breaking change*
* `classloader-hierarchy` now starts at the current thread context classloader
  instead of Clojure's "baseLoader". *breaking change*
* New `resources` and `classloader-resources` fns for determining from which
  classloader(s) a given resource is available (gh-48)

**Aether API (`cemerick.pomegranate.aether` and friends)**

* `install-artifacts` and `deploy-artifacts` are now generalized to operate
  over multiple files (vs. the prior assumptions re: an artifact + a POM)
(gh-52)
* `resolve-dependencies*` now available that returns the bare Aether results of
  dependency resolution, _sans_ Clojure-friendly graphification (gh-50)
* `resolve-dependencies`, `install-artifacts`, and `deploy-artifacts` now
  accept an optional `:repository-session-fn` to potentially modify the Aether
`RespositorySystemSession` prior to its use (gh-56)
  
