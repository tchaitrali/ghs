
# GHSearch Platform

This project is made of two subprojects:
1. `application`: The main application has two main responsibilities:
    1. Crawling GitHub and retrieving repository information. This can be disabled with `app.crawl.enabled` argument.
    2. Serving as the backend server for website/frontend
2. `front-end`: A frontend for searching the database (http://seart-ghs.si.usi.ch)

## Setup & Run Project Locally (for development)

The detailed instruction can be find [here](./README_SETUP.md)


## Dockerisation :whale:

GHS service is composed of three containers:

| Service name | Container name | Goal |
| ------------ | -------------- | ---- |
| `gse-app` | `gse-app` | for the spring application itself |
| `gse-fe` | `gse-fe` | for supplying the front end files |
| `gse-db` | `gse-db` | for the database |

The docker-compose configuration `docker-compose.yml` is configured to automatically run scripts to import the previous backup of the database.

### Build and Deploy

1. Configure Crawler with GitHub access token and programming languages (See [Here](./README_SETUP.md#2-setup-crawler))

2. Build the backend as `jar` file: 
```shell
mvn clean package
```

3. To deploy back-end image use the following commands (the tailing `.` should refers to root of the project):
```shell
docker build -t ghs-backend:latest -f docker/Dockerfile.be .
```

4. To deploy front-end images, simply run (the tailing `.` should refers to root of the project):
```shell
docker build -t ghs-frontend:latest -f docker/Dockerfile.fe .
```

5. On server side, or the machine you want to deploy on:
   1. (first time only) Copy `docker-compose` folder on your server.
   2. Fetch new image(s) you just built: `docker-compose pull`
   3. The, run it: `docker-compose up`


## More Info on Flyway and Database Migration
Read [here](./README_flyway.md)

## FAQ
- **How add a new programming language to platform?**
  1. See the "Adding C#" commit on December 17th 2020.
  2. Create a new Flyway migration file to insert a new language row on `supported_languages` table.
   

