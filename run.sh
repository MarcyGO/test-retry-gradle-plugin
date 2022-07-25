# publish new build to mavenLocal
baseDir=$(pwd)
projectBuild=/home/xinyuwu4/simple_project/build.gradle
./gradlew publishToMavenLocal > temp.log

grep "BUILD SUCCESSFUL" temp.log
if [ $? == 0 ]; then
    ver=$(grep version temp.log | grep -o '[[:digit:]].*$')
    original=$(grep "org.gradle:test-retry-gradle-plugin:" ${projectBuild})
    sed -i "s/${original}/    classpath \"org.gradle:test-retry-gradle-plugin:${ver}\"/" ${projectBuild} 

    cd /home/xinyuwu4/simple_project
    ./cht pass retry test
fi 