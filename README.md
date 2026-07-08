# IdrakOCRProcessor
External jar that can be scheduled to run daily on a specific time, or do OCR on scanned documents

The following command is required to include the classes from jace.jar in the package:
mvn install:install-file -Dfile=[put the project folder path here]\lib\Jace.jar -DgroupId=IBM -Dversion=1 -DartifactId=jace -Dpackaging=jar

Tesseract must be downloaded and installed on the machine, and JAVA_HOME needs be added to the environemtn variables. 

Configure the config.properties file in the project

Idrak OCR login is the first step in the processor run. Configure these properties before running:

idrak-token-url
idrak-client-id
idrak-client-secret
idrak-grant-type
idrak-scope
idrak-cookie

# VERY IMPORTANT NOTE: Before the first time you run mvn install, run the following commans:
mvn install:install-file -Dfile=${basedir}/lib/Jace.jar -DgroupId=com.ibm -DartifactId=jace -Dversion=1.0 -Dpackaging=jar


# create task scheduler from IdrakOCRProcessor jar
Action :
		program/script 	:  	<jar file path> \idrak.ocr.processor-1-jar-with-dependencies.jar
		argument script	:	-Dfile.encoding=utf-8
		
Triggers:
		"daily"
		"repeat task every" 5 minutes with "Indefinitely" for duration of service
	
	
