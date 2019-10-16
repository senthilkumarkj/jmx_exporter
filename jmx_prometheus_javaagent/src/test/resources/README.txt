To generate test keystore, use the following commands.

openssl req  -nodes -new -x509 -keyout /tmp/keystore.key -out /tmp/keystore.pem -subj '/CN=testprometheus' -days 3650
KEYSTOREPASS=$(openssl rand -base64 12)
openssl pkcs12 -export -in /tmp/keystore.pem -inkey /tmp/keystore.key -out /tmp/keystore.p12 -passout pass:"${KEYSTOREPASS}"