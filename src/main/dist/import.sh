#import AffyMetrix ids for rat, mouse, human and other species (if available) into ALIASES table
APPNAME="array-id-import-pipeline"
APPDIR=/home/rgddata/pipelines/$APPNAME

SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`
if [ "$SERVER" == "REED" ]; then
  EMAIL_LIST=rgd.devops@mcw.edu
else
  EMAIL_LIST=mtutaj@mcw.edu
fi

cd $APPDIR

java -Dspring.config=$APPDIR/../properties/default_db2.xml \
    -Dlog4j.configurationFile=file://$APPDIR/properties/log4j2.xml \
    -jar lib/$APPNAME.jar "$@" | tee run.log 2>&1

mailx -s "[$SERVER] Array Id Import Pipeline OK" < $APPDIR/logs/summary.log $EMAIL_LIST
