#!/usr/bin/env bash
mvn clean
mvn compile
mvn -e exec:java -Dexec.mainClass="edu.uestc.antfuzzer.Main" -Dexec.args="-fuzzingConfigFile ./config/test/test.json"