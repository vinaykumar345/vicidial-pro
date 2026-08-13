FROM php:8.2-apache

RUN a2enmod rewrite
RUN docker-php-ext-install mysqli
RUN sed -ri 's/AllowOverride None/AllowOverride All/g' /etc/apache2/apache2.conf
RUN printf "FallbackResource /index.php\n" > /etc/apache2/conf-available/front-controller.conf \
	&& a2enconf front-controller

WORKDIR /var/www/html

COPY public/ /var/www/html/
COPY src/ /var/www/src/
COPY .env.example /var/www/.env.example

EXPOSE 80
