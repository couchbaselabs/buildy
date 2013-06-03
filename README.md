# buildy

build dashboard thingy

# building

With [Leiningen](https://github.com/technomancy/leiningen)

    lein uberjar

`.jar` file with `-standalone` in filename is generated in `target/`.

Then, anywhere with a JVM

    java -jar buildy-something-standalone.jar

Or just

    lein run

To run without building a .jar (the standalone .jar is for running
without leiningen)
