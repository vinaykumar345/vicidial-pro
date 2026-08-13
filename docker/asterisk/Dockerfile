FROM andrius/asterisk:latest

COPY docker/asterisk/start.sh /usr/local/bin/vicidial-asterisk-start.sh
RUN chmod +x /usr/local/bin/vicidial-asterisk-start.sh

ENTRYPOINT ["/usr/local/bin/vicidial-asterisk-start.sh"]
