#!/bin/sh
#
# Copyright (c) 2016 EMC Corporation
# All Rights Reserved
#

#
# Script to help manage Unity/VNXe storage system outside of ViPR.
# Used to perform various operations.
#
# Usage: ./vnxehelper.sh verify_export <NAME_PATTERN> <NUMBER_OF_INITIATORS_EXPECTED> <NUMBER_OF_LUNS_EXPECTED>
#        ./vnxehelper.sh add_volume_to_mask <DEVICE_ID> <NAME_PATTERN>
#        ./vnxehelper.sh remove_volume_from_mask <DEVICE_ID> <NAME_PATTERN>
#        ./vnxehelper.sh delete_volume <DEVICE_ID>
#        ./vnxehelper.sh delete_mask <NAME_PAATTERN>
#        ./vnxehelper.sh add_initiator_to_mask <NWWN:PWWN> <NAME_PATTERN>
#        ./vnxehelper.sh remove_initiator_from_mask <PWWN> <NWWN> <NAME_PATTERN>
#
#set -x

TMPFILE1=/tmp/verify-${RANDOM}
TMPFILE2=$TMPFILE1-error
NUMBER_OF_INITIATORS = "numberOfInitiators"
NUMBER_OF_LUNS = "numberOfLUNs"

## Convenience method for deleting a mask outside of ViPR (including the storage group)
delete_mask() {
    pattern=$1
    java -Dproperty.file=${tools_file} -jar ${tools_jar} -arrays ${array_type} -method delete_mask -params "${pattern}" 
    echo "Deleted lun mapping/initiator group ${pattern}"
}

add_volume_to_mask() {
    device_id=$1
    pattern=$2
    hlu="-1"
    if [[ $# -gt 2 ]]; then
        hlu="$3"
    fi

    java -Dproperty.file=${tools_file} -jar ${tools_jar} -arrays ${array_type} -method add_volume_to_mask -params "${pattern},${device_id},${hlu}" > ${TMPFILE1} 2> ${TMPFILE2}
    echo "Added volume ${device_id} to initiator group ${pattern}"
}

remove_volume_from_mask() {
    device_id=$1
    pattern=$2
    java -Dproperty.file=${tools_file} -jar ${tools_jar} -arrays ${array_type} -method remove_volume_from_mask -params "${pattern},${device_id}"
    echo "Removed volume ${device_id} from initiator group ${pattern}"
}

delete_volume() {
    device_id=$1
    java -Dproperty.file=${tools_file} -jar ${tools_jar} -arrays ${array_type} -method delete_volume -params "${device_id}" 
}

remove_initiator_from_mask() {
    pwwn=$1
    pattern=$2
    java -Dproperty.file=${tools_file} -jar ${tools_jar} -arrays ${array_type} -method remove_initiator_from_mask -params "${pattern},${pwwn}"

}

add_initiator_to_mask() {
    pwwn=$1
    pattern=$2
    java -Dproperty.file=${tools_file} -jar ${tools_jar} -arrays ${array_type} -method add_initiator_to_mask -params "${pattern},${pwwn}"

}

verify_export() {
    # Parameters: Storage View Name Name, Number of Initiators, Number of Luns
    # If checking if the Storage View does not exist, then parameter $2 should be "gone"
    HOST_INITIATORS=$1
    NUM_INITIATORS=$2
    NUM_LUNS=$3

    java -Dproperty.file=${tools_file} -jar ${tools_jar} -arrays ${array_type} -method get_mask_info -params "${HOST_INITIATORS}" > ${TMPFILE1} 2> ${TMPFILE2}
    grep -n "${HOST_INITIATORS}" ${TMPFILE1} > /dev/null
    # 0 if line selected, 1 if no line selected
    foundIt=$?
    if [ -s $TMPFILE1 ]; then
        if [ $foundIt -ne 0 ]; then
            if [ "$2" = "gone" ]; then
                echo "PASSED: Verified host maksing with pattern ${HOST_INITIATORS} doesn't exist."
                exit 0;
            fi
            echo -e "\e[91mERROR\e[0m: I Expected host masking ${HOST_INITIATORS}, but could not find it";
            exit 1;
        else
            if [ "$2" = "gone" ]; then
                echo -e "\e[91mERROR\e[0m: Expected host masking ${HOST_INITIATORS} to be gone, but it was found"
                exit 1;
            fi
        fi
    else
        echo -e "\e[91mERROR\e[0m: empty or invalid response from ${array_type}"
        if [ -s $TMPFILE2 ]; then
            cat $TMPFILE2
        fi
        exit 1;
    fi

    num_inits=`grep ${NUMBER_OF_INITIATORS} ${TMPFILE1} | awk -F: '{print $2}'`
    num_luns=`grep ${NUMBER_OF_LUNS} ${TMPFILE1} | awk -F: '{print $2}'`
    failed=false

    if [ ${num_inits} -ne ${NUM_INITIATORS} ]
	then
	echo -e "\e[91mERROR\e[0m: Host initiators: Expected: ${NUM_INITIATORS}, Retrieved: ${num_inits}";
	failed=true
    fi

    if [ ${num_luns} -ne ${NUM_LUNS} ]
	then
	echo -e "\e[91mERROR\e[0m: Host luns: Expected: ${NUM_LUNS}, Retrieved: ${num_luns}";
	failed=true
    fi

    if [ "${failed}" = "true" ]
	then
	exit 1;
    fi

    echo "PASSED: Host masking '$HOST_INITIATORS' contained ${NUM_INITIATORS} initiators and ${NUM_LUNS} luns"
    exit 0;
}

# Check to see if this is an operational request or a verification of export request
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
tools_file="${DIR}/tools.yml"
tools_jar="${DIR}/ArrayTools.jar"
array_type="unity"

if [ -f ${tools_file} ]; then
    echo "Using configuration file ${tools_file}"
    is_vnxe=$(grep "vnxe" ${tools_file} || echo "")
    if [ "$is_vnxe" != '' ]; then
        array_type="vnxe"
    fi
else
    echo "${tools_file} not found"
    exit 1
fi

if [ -f ${tools_jar} ]; then
    echo "Using jar file ${tools_jar}"
else
    echo "${tools_jar} not found"    
    exit 1
fi

if [ "$1" = "add_volume_to_mask" ]; then
    shift
    add_volume_to_mask "$1" "$2" "$3"
elif [ "$1" = "remove_volume_from_mask" ]; then
    shift
    remove_volume_from_mask "$1" "$2"
elif [ "$1" = "add_initiator_to_mask" ]; then
    shift
    add_initiator_to_mask "$1" "$2"
elif [ "$1" = "remove_initiator_from_mask" ]; then
    shift
    remove_initiator_from_mask "$1"
elif [ "$1" = "delete_volume" ]; then
    shift
    delete_volume "$1"
elif [ "$1" = "delete_mask" ]; then
    shift
    delete_mask "$1"
elif [ "$1" = "verify_export" ]; then
    shift
    verify_export "$1" "$2" "$3"
else
    echo "Usage: $0 [delete_mask | add_volume_to_mask | remove_volume_from_mask | add_initiator_to_mask | remove_initiator_from_mask | delete_volume | verify_export] {params}"
fi
