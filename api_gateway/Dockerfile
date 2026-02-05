FROM apache/apisix:latest

USER root

# Install JDK 21 from Amazon Corretto
RUN apt-get update && \
    apt-get install -y wget gnupg && \
    # 1. Import the Corretto public key
    wget -O - https://apt.corretto.aws/corretto.key | gpg --dearmor -o /usr/share/keyrings/corretto-keyring.gpg && \
    # 2. Add the Corretto repository
    echo "deb [signed-by=/usr/share/keyrings/corretto-keyring.gpg] https://apt.corretto.aws stable main" | tee /etc/apt/sources.list.d/corretto.list && \
    # 3. Install JDK 21
    apt-get update && apt-get install -y java-21-amazon-corretto-jdk && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Set Java environment variables
ENV JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto-amd64
ENV PATH="$JAVA_HOME/bin:$PATH"

USER apisix