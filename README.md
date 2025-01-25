# Common Log4j Appender

![Quality Gate](https://github.com/chavaillaz/common-log4j-appender/actions/workflows/sonarcloud.yml/badge.svg)
![Dependency Check](https://github.com/chavaillaz/common-log4j-appender/actions/workflows/snyk.yml/badge.svg)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.chavaillaz/common-log4j-appender/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.chavaillaz/common-log4j-appender)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

This library helps you to easily create Appenders for Log4j.

## Installation

The dependency is available in maven central (see badge for version):

```xml
<dependency>
    <groupId>com.chavaillaz</groupId>
    <artifactId>common-log4j-appender</artifactId>
</dependency>
```

## Usage

Three classes will be needed to create your appender:
- Implementing `LogDelivery` or extending `AbstractBatchLogDelivery` to ship the logs with the following methods:
  - **send**: Send a log event (possibly stacked before being sent)
  - **flush**: Flush any buffer to send all remaining log events
- Extending `AbstractLogDeliveryAppender` to create the appender with the following methods:
  - **createLogDeliveryHandler**: Create a new instance of your log delivery above
  - **createLogDeliveryTask**: Create a new instance of a log delivery task to send a log event
- Implementing `LogConfiguration` to configure the appender, by adding any configuration needed for the log shipping

## Example

Two implementation examples are available:
- [ElasticSearch Appender](https://github.com/chavaillaz/elasticsearch-log4j-appender)
- [OpenSearch Appender](https://github.com/chavaillaz/opensearch-log4j-appender)

## Contributing

If you have a feature request or found a bug, you can:

- Write an issue
- Create a pull request

If you want to contribute then

- Please write tests covering all your changes
- Ensure you didn't break the build by running `mvn test`
- Fork the repo and create a pull request

## License

This project is under Apache 2.0 License.