# amqp

This project contains simple amqp produce application and test to reproduce issue where messages are not anymore produced to broker.

# Quarkus version
Quarkus version 3.10.1

# ActiveMQ
ActiveMQ amqp transport.producerCredit is set to 100 to limit the time it takes to run ouf of credits.

# MyMessagingApplicationTest
This test continuously produces messages and restarts the activemq container every 20 seconds. Depending how lucky you are the test might fail after first 100 messages.

