# Common Log4j Appender

![Quality Gate](https://github.com/chavaillaz/common-log4j-appender/actions/workflows/sonarcloud.yml/badge.svg)
![Dependency Check](https://github.com/chavaillaz/common-log4j-appender/actions/workflows/snyk.yml/badge.svg)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.chavaillaz/common-log4j-appender/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.chavaillaz/common-log4j-appender)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Library for the creation of Appenders for Log4j

## Installation

The dependency is available in maven central (see badge for version):

```xml
<dependency>
    <groupId>com.chavaillaz</groupId>
    <artifactId>common-log4j-appender</artifactId>
</dependency>
```

## Usage

Create a new appender by extending the `AbstractLogDeliveryAppender` class and implementing the following methods:
- **createDeliveryHandler**: Create a new instance of a delivery handler implementing `LogDelivery`.
- **createDeliveryTask**: Create a new instance of a delivery task to send a log event.

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