FROM php:5.6-apache

ENV DEBIAN_FRONTEND=noninteractive

RUN printf 'deb [trusted=yes] http://archive.debian.org/debian stretch main\n' > /etc/apt/sources.list \
    && printf 'Acquire::Check-Valid-Until "false";\nAcquire::AllowInsecureRepositories "true";\nAcquire::AllowDowngradeToInsecureRepositories "true";\n' > /etc/apt/apt.conf.d/99archive

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        default-mysql-client \
        libperl-dev \
        libdbd-mysql-perl \
        libnet-telnet-perl \
        perl \
        cron \
        curl \
        netcat-traditional \
    && docker-php-ext-install mysql mysqli \
    && a2enmod rewrite \
    && rm -rf /var/lib/apt/lists/*

# Global PHP timezone for all Vicidial scripts.
RUN printf 'date.timezone=Asia/Kolkata\n' > /usr/local/etc/php/conf.d/99-timezone.ini

# Vicidial web application
COPY www/ /var/www/html/

# Runtime assets frequently used by Vicidial scripts
COPY agi/ /opt/vicidial/agi/
COPY bin/ /opt/vicidial/bin/
COPY libs/ /opt/vicidial/libs/
COPY sounds/ /opt/vicidial/sounds/
COPY docker/web/entrypoint.sh /usr/local/bin/vicidial-entrypoint.sh
COPY docker/vicidial-daemons/start.sh /opt/vicidial/docker/vicidial-daemons/start.sh

RUN chown -R www-data:www-data /var/www/html \
    && chmod -R 755 /opt/vicidial \
    && chmod +x /usr/local/bin/vicidial-entrypoint.sh \
    && chmod +x /opt/vicidial/docker/vicidial-daemons/start.sh

WORKDIR /var/www/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD nc -z 127.0.0.1 80 || exit 1

ENTRYPOINT ["/usr/local/bin/vicidial-entrypoint.sh"]
CMD ["apache2-foreground"]
