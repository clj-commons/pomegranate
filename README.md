# Pomegranate  [![Travis CI status](https://secure.travis-ci.org/cemerick/pomegranate.png)](https://travis-ci.org/cemerick/pomegranate/builds)

[Pomegranate](https://github.com/cemerick/pomegranate) is a library that
provides:

1. A sane Clojure API for
   [Maven-resolver](https://maven.apache.org/resolver/) (originally called Aether).
2. A re-implementation of
   [`add-classpath`](https://clojure.github.com/clojure/clojure.core-api.html#clojure.core/add-classpath)
   (deprecated in Clojure core) that:

* is a little more comprehensive than core's `add-classpath` — it should work as
  expected in more circumstances, and
* optionally uses Maven-resolver to add a Maven artifact (and all of its transitive
  dependencies) to your Clojure runtime's classpath dynamically.

Insofar as most useful Clojure libraries have dependencies, any reasonable
implementation of the `add-classpath` concept must seamlessly support resolving
those dependencies IMO.

## "Installation"

Pomegranate is available in Maven central.  Add it to your Leiningen
`project.clj`:

```clojure
[com.cemerick/pomegranate "1.1.0"]
```

or to your Maven project's `pom.xml`:

```xml
<dependency>
  <groupId>com.cemerick</groupId>
  <artifactId>pomegranate</artifactId>
  <version>1.1.0</version>
</dependency>
```

## `add-classpath` usage

Just to set a stage: you're at the REPL, and you've got some useful data that
you'd like to munge and analyze in various ways.  Maybe it's something you've
generated locally, maybe it's data on a production machine and you're logged in
via [nREPL](https://github.com/clojure/tools.nrepl).  In any case, you'd like to
work with the data, but realize that you don't have the libraries you need do
what you want.  Your choices at this point are:

1. Dump the data to disk via `pr` (assuming it's just Clojure data structures!),
   and start up a new Clojure process with the appropriate libraries on the
   classpath. This can really suck if the data is in a remote environment.
2. There is no second choice.  You _could_ use `add-claspath`, but the library
   you want has 12 bajillion dependencies, and there's no way you're going to
   hunt them down manually.

Let's say we want to use [Incanter](https://github.com/liebke/incanter) (which
has roughly 40 dependencies — far too many for us to reasonably locate and add
via `add-classpath` manually):

```clojure
=> (require '(incanter core stats charts))
#<CompilerException java.io.FileNotFoundException:
  Could not locate incanter/core__init.class or incanter/core.clj on classpath:  (NO_SOURCE_FILE:0)>
```

Looks bleak. Assuming you've got Pomegranate on your classpath already, you can
do this though:

```clojure
=> (use '[cemerick.pomegranate :only (add-dependencies)])
nil
=> (add-dependencies :coordinates '[[incanter "1.9.2"]]
                     :repositories (merge cemerick.pomegranate.aether/maven-central
                                          {"clojars" "https://clojars.org/repo"}))
;...add-dependencies returns full dependency graph...
=> (require '(incanter core stats charts))
nil
```

Now you can analyze and chart away, Incanter having been added to your runtime.
Note that `add-dependencies` may crunch along for a while — it may need to
download dependencies, so you're waiting on the network.  All resolved
dependencies are stored in the default local repository (`~/.m2/repository`),
and if they are found there, then they are not downloaded.

The arguments to `add-dependencies` look like Leiningen-style notation, and they
are.

Please note that **there are a number of scenarios in which `add-dependencies`
will not work, or will not work as you'd expect**.  Many of these are due to the
nature of JVM classloaders (e.g. adding jars containing conflicting versions of
a particular dependency will rarely end well), which Pomegranate does not
currently attempt to hide.  Thus, `add-classpath` and `add-dependencies` should
be considered escape hatches to be used when necessary, rather than a regular
part of your development workflow.

#### `URLClassLoader` modifiability

Starting with pomegranate `1.0.0`, `java.net.URLClassLoader`s are not modifiable
by default. This is to accommodate new reflection policy starting with JDK 9
(which currently issues an ugly warning on reflective access to non-public
methods, but future releases will simply fail). If you are in a situation where
you are using pomegranate but do not have a `clojure.lang.DynamicClassLoader`
visible in your classloader hierarchy, you will need to explicitly enable the
`java.net.URLClassLoader` support in
[dynapath](https://github.com/tobias/dynapath) (upon which pomegranate relies
for such things).

## Status of Aether support

Pomegranate is being used by [Leiningen v2.x](https://leiningen.org) as
its sole dependency resolution library.  This has prompted rapid
maturation of the scope and quality of Aether support.  That said,
specific API points remain subject to change as we find the right
abstractions and conventions.

#### Supported features

* dependency resolution
  * common dependency graph/hierarchy manipulation ops
* local installation
* remote deployment
* repository authentication for all of the above
* HTTP proxy configuration
* offline mode
* transfer listeners (with a sane Clojure fn veneer)

#### Not there yet

* repository listeners
* options to retrieve a single artifact (e.g. for obtaining source/javadoc)
* tests; there's halfway decent coverage, but nowhere near the kind of
  comprehensive combinatorial testing that maven dependency resolution demands

## Changelog

See the `CHANGES.md` file at the top level of the repo.

## License

Copyright © 2011-2017 [Chas Emerick](https://cemerick.com) and all other
contributors.

Licensed under the EPL. (See the file epl-v10.html.)
