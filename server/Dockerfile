FROM node:22-alpine
WORKDIR /srv/br
COPY package.json ./
COPY src ./src
ENV PORT=8080 TCP_PORT=7777 DATA_DIR=/data
VOLUME /data
EXPOSE 8080/tcp 7777/tcp
USER node
CMD ["node", "src/server.js"]
