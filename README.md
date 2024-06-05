# amqp

This project contains simple amqp produce application and test to reproduce issue where messages are not anymore produced to broker.

# Quarkus version
Quarkus version 3.10.1

# ActiveMQ
ActiveMQ amqp transport.producerCredit is set to 100 to limit the time it takes to run ouf of credits.

# NoMoreRequestsTest
Tries to reproduce the issue where producer does not anymore produce messages

# ConnectionsDuringDowntimeTest
Tries to reproduce the issue where amount of connections grows during broker downtime

# HealthCheckTest
Tries to reproduce the issue where amount of connections grows during broker downtime due to health check endpoint calls



