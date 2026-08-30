FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY CodeSentinelServer.java .

RUN javac CodeSentinelServer.java

EXPOSE 8080

CMD ["java", "CodeSentinelServer"]
