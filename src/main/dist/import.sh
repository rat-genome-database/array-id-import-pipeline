#import AffyMetrix ids for rat, mouse, human and other species (if available) into ALIASES table

APPDIR=/home/rgddata/pipelines/ArrayIdImport

SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`
if [ "$SERVER" == "REED" ]; then
  EMAIL_LIST=RGD.Developers@mcw.edu
else
  EMAIL_LIST=mtutaj@mcw.edu
fi

$APPDIR/_run.sh "$@"

mailx -s "[$SERVER] Array Id Import Pipeline Run " < $APPDIR/run.log $EMAIL_LIST
