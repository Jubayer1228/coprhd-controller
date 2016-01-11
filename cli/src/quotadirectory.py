#!/usr/bin/python

#
# Copyright (c) 2013 EMC Corporation
# All Rights Reserved
#
# This software contains the intellectual property of EMC Corporation
# or is licensed to EMC Corporation from third parties.  Use of this
# software and the intellectual property contained therein is expressly
# limited to the terms and conditions of the License Agreement under which
# it is provided by or on behalf of EMC.
#

import common
import json
import fileshare
import socket
import time
from threading import Timer
from common import SOSError
import sys

'''
The class definition for the operation on the ViPR QuotaDirectory
'''


class QuotaDirectory(object):

    # All URIs for the Quota Directory operations
    URI_QUOTA_DIRECTORY = "/file/filesystems/{0}/quota-directories"
    URI_QUOTA_DIRECTORY_SHOW = "/file/quotadirectories/{0}"
    URI_QUOTA_DIRECTORY_DELETE = "/file/quotadirectories/{0}/deactivate"
    URI_QUOTA_DIRECTORY_UPDATE = "/file/quotadirectories/{0}"
    URI_QUOTA_DIRECTORY_TASKS_BY_OPID = '/vdc/tasks/{0}'

    isTimeout = False
    timeout = 300


    def __init__(self, ipAddr, port):
        '''
        Constructor: takes IP address and port of the ViPR instance. These are
        needed to make http requests for REST API
        '''
        self.__ipAddr = ipAddr
        self.__port = port

    """
    quotadirectory create operation
    """

    def create(self, ouri, name, size, oplock, securitystyle, sync):
        parms = {
            'name': name,
        }
        if(size):
            size = common.to_bytes(size)
            parms["size"] = size
        if(oplock):
            parms["oplock"] = oplock
        if(securitystyle):
            parms["security_style"] = securitystyle
            
        body = json.dumps(parms)

        # REST api call
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "POST",
            QuotaDirectory.URI_QUOTA_DIRECTORY.format(ouri), body)

        o = common.json_decode(s)

        if(sync):
            return (
                self.block_until_complete(
                    o['resource']['id'],
                    o["id"],sync)
            )
        else:
            return o

    """
    quotadirectory update operation
    """

    def update(self, ouri, name, size, oplock, securitystyle, sync):
        qduri = self.quotadirectory_query(ouri, name)
	    
        params = dict()
        if(size):
            size = common.to_bytes(size)
	    params['size'] = size
        if(oplock):
            params['oplock'] = oplock
        if(securitystyle):
            params['security_style'] = securitystyle
     
        body = json.dumps(params)
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "PUT",
            QuotaDirectory.URI_QUOTA_DIRECTORY_UPDATE.format(qduri), body)

        o = common.json_decode(s)

        if(sync):
            return (
                self.block_until_complete(
                    o['resource']['id'],
                    o["id"],sync)
            )
        else:
            return o

    def delete(self, ouri, name, forcedelete, sync):
        qduri = self.quotadirectory_query(ouri, name)
        body = None    
        params = dict()
        if(forcedelete):
            params['forceDelete'] = true
     
        body = json.dumps(params)

        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "POST",
            QuotaDirectory.URI_QUOTA_DIRECTORY_DELETE.format(qduri), body)

        o = common.json_decode(s)

        if(sync):
            return (
                self.block_until_complete(
                    o['resource']['id'],
                    o["id"],sync)
            )
        else:
            return o


    def quotadirectory_list(self, resourceUri):
        if(resourceUri is not None):
            return self.quotadirectory_list_uri(resourceUri)
        return None


    def quotadirectory_list_uri(self, ouri):
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "GET",
            QuotaDirectory.URI_QUOTA_DIRECTORY.format(ouri), None)
        o = common.json_decode(s)
        return o['quota_dir']

    def quotadirectory_show(self, resourceUri, name, xml):
        qdUri = self.quotadirectory_query(
            resourceUri,
            name)
        return (self.quotadirectory_show_uri(qdUri, xml))

    def quotadirectory_show_uri(self, suri, xml=False):
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "GET",
            QuotaDirectory.URI_QUOTA_DIRECTORY_SHOW.format(suri), xml)
        if(xml is False):
            o = common.json_decode(s)
            if(False == o['inactive']):
                return o
            else:
                return None
        else:
            return s

    def quotadirectory_query(self, resuri, quotadirectoryName):
        if(resuri is not None):
            uris = self.quotadirectory_list_uri(resuri)
            for uri in uris:
                if (uri['name'] == quotadirectoryName):
                    return uri['id']

        raise SOSError(
            SOSError.SOS_FAILURE_ERR,
            "quotadirectory with the name:" +
            quotadirectoryName +
            " Not Found ")

    def storageResource_query(self,
                              fileshareName,
                              project,
                              tenant):
        resourcepath = "/" + project + "/"
        if(tenant is not None):
            resourcepath = tenant + resourcepath
        resourceObj = None
        resourceObj = fileshare.Fileshare(self.__ipAddr, self.__port)
        return (resourceObj.fileshare_query(resourcepath + fileshareName))
        

    def quotadirectory_show_task_opid(self, taskid):
        (s, h) = common.service_json_request(
            self.__ipAddr, self.__port,
            "GET",
            QuotaDirectory.URI_QUOTA_DIRECTORY_TASKS_BY_OPID.format(taskid),
            None)
        if (not s):
            return None
        o = common.json_decode(s)
        return o

    def timeout_handler(self):
        self.isTimeout = True

    def block_until_complete(self, resuri, task_id,sync):
        t = Timer(sync, self.timeout_handler)
        t.start()
        while(True):
            #out = self.show_by_uri(id)
            out = self.quotadirectory_show_task_opid(task_id)

            if(out):
                if(out["state"] == "ready"):
                    # cancel the timer and return
                    t.cancel()
                    break
                # if the status of the task is 'error' then cancel the timer
                # and raise exception
                if(out["state"] == "error"):
                    # cancel the timer
                    t.cancel()
                    error_message = "Please see logs for more details"
                    if("service_error" in out and
                       "details" in out["service_error"]):
                        error_message = out["service_error"]["details"]
                    raise SOSError(
                        SOSError.VALUE_ERR,
                        "Task: " +
                        task_id +
                        " is failed with error: " +
                        error_message)

            if(self.isTimeout):
                print "Operation timed out"
                self.isTimeout = False
                break
        return

    # Indentation END for the class


# Start Parser definitions
def create_parser(subcommand_parsers, common_parser):
    # create command parser
    create_parser = subcommand_parsers.add_parser(
        'create',
        description='ViPR Quotadirectory create CLI usage',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Creates a Quotadirectory for the given filesystem')

    mandatory_args = create_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument(
        '-n', '-name',
        help='Name of the Quota Directory',
        metavar='<name>',
        dest='name',
        required=True)
    mandatory_args.add_argument(
        '-fs', '-filesystem',
        help='Name of the Filesystem',
        metavar='<filesystem>',
        dest='filesystem',
        required=True)
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of Project',
                                required=True)
    create_parser.add_argument('-tenant', '-tn',
                               metavar='<tenantname>',
                               dest='tenant',
                               help='Name of tenant')
    create_parser.add_argument('-size', '-s',
                               metavar='<size>',
                               dest='size',
                               help='Size of Quotadirectory')

    create_parser.add_argument('-oplk', '-oplock ',
                               choices=["true", "false"],
                               metavar='<oplock>',
                               dest='oplock',
                               help='Oplock for Quotadirectory')

    create_parser.add_argument('-secsy', '-securitystyle',
                               choices=["unix", "ntfs", "mixed"],
                               help='Quota Directory Security Style ',
                               dest='securitystyle',
                               metavar='<securitystyle>')
    create_parser.add_argument('-synchronous', '-sync',
                               dest='synchronous',
                               help='Synchronous quotadirectory create',
                               default=0,type=int)

    create_parser.set_defaults(func=quotadirectory_create)



'''
Preprocessor for the quotadirectory create operation
'''

def quotadirectory_create(args):
    obj = QuotaDirectory(args.ip, args.port)
  
    try:
        resourceUri = obj.storageResource_query(
            args.filesystem,
            args.project,
            args.tenant)

        obj.create(resourceUri, args.name, args.size, args.oplock, 
		   args.securitystyle, args.synchronous)

    except SOSError as e:
        if (e.err_code == SOSError.SOS_FAILURE_ERR):
            raise SOSError(
                SOSError.SOS_FAILURE_ERR,
                "QuotaDirectory: " +
                args.name +
                ", Create Failed\n" +
                e.err_text)
        else:
            common.format_err_msg_and_raise(
                "create",
                "quotadirectory",
                e.err_text,
                e.err_code)


# list command parser
def list_parser(subcommand_parsers, common_parser):
    list_parser = subcommand_parsers.add_parser(
        'list',
        description='ViPR QuotaDirectory List CLI usage',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Lists QuotaDirectory for the given filesystem')
    mandatory_args = list_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument(
        '-fs', '-filesystem',
        help='Name of the Filesystem',
        metavar='<filesystem>',
        dest='filesystem',
        required=True)
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of Project',
                                required=True)
    list_parser.add_argument('-tenant', '-tn',
                             metavar='<tenantname>',
                             dest='tenant',
                             help='Name of tenant',
                             required=False)
    list_parser.add_argument('-v', '-verbose',
                             dest='verbose',
                             action='store_true',
                             help='Lists QuotaDirectories with details')
    list_parser.add_argument('-l', '-long',
                             dest='long',
                             action='store_true',
                             help='Lists QuotaDirectories in a large table')
    list_parser.set_defaults(func=quotadirectory_list)


'''
Preprocessor for Quotadirectory list operation
'''

def quotadirectory_list(args):
    obj = QuotaDirectory(args.ip, args.port)    
    try:
        resourceUri = obj.storageResource_query(
            args.filesystem,
            args.project,
            args.tenant)

        uris = obj.quotadirectory_list(resourceUri)
        records = []
        for uri in uris:
            quotadirectory_obj = obj.quotadirectory_show_uri(uri['id'])
            if(quotadirectory_obj is not None):
                records.append(quotadirectory_obj)

        if(len(records) > 0):
            if(args.verbose is True):
                return common.format_json_object(records)
            else:
                from common import TableGenerator
                if(args.long is True):
                    TableGenerator(records, ['name', 'quota_size_gb', 'oplock', 'security_style']).printTable()    
                else:
                    TableGenerator(records, ['name', 'quota_size_gb']).printTable()
        else:
            return

    except SOSError as e:
        common.format_err_msg_and_raise(
            "list",
            "quotadirectory",
            e.err_text,
            e.err_code)


# show command parser
def show_parser(subcommand_parsers, common_parser):
    show_parser = subcommand_parsers.add_parser(
        'show',
        description='ViPR Quotadirectory Show CLI usage',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Show Quotadirectory details')
    show_parser.add_argument('-xml',
                             dest='xml',
                             action='store_true',
                             help='XML response')
    mandatory_args = show_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument(
        '-fs', '-filesystem',
        help='Name of the Filesystem',
        metavar='<filesystem>',
        dest='filesystem',
        required=True)
    mandatory_args.add_argument('-name', '-n',
                       metavar='<quotadirectoryname>',
                       dest='name',
                       help='Name of Quotadirectory',
                       required=True)

    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of Project',
                                required=True)
    show_parser.add_argument('-tenant', '-tn',
                               metavar='<tenantname>',
                               dest='tenant',
                               help='Name of tenant')

    show_parser.set_defaults(func=quotadirectory_show)


def quotadirectory_show(args):
    obj = QuotaDirectory(args.ip, args.port)    
    try:
        resourceUri = obj.storageResource_query(
            args.filesystem,
            args.project,
            args.tenant)
        respContent = obj.quotadirectory_show(
            resourceUri,
            args.name,
            args.xml)

        if(args.xml):
            return common.format_xml(respContent)
        else:
            return common.format_json_object(respContent)

    except SOSError as e:
        if (e.err_code == SOSError.SOS_FAILURE_ERR):
            raise SOSError(
                SOSError.SOS_FAILURE_ERR,
                "quotadirectory " +
                args.name +
                ": Not Found")
        else:
            common.format_err_msg_and_raise(
                "show",
                "quotadirectory",
                e.err_text,
                e.err_code)


def delete_parser(subcommand_parsers, common_parser):
    delete_parser = subcommand_parsers.add_parser(
        'delete',
        description='ViPR Quotadirectory delete CLI usage ',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Deletes Quotadirectory')
    mandatory_args = delete_parser.add_argument_group('mandatory arguments')

    mandatory_args.add_argument(
        '-n', '-name',
        help='Name of the Quota Directory',
        metavar='<name>',
        dest='name',
        required=True)
    mandatory_args.add_argument(
        '-fs', '-filesystem',
        help='Name of the Filesystem',
        metavar='<filesystem>',
        dest='filesystem',
        required=True)
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of Project',
                                required=True)
    delete_parser.add_argument('-tenant', '-tn',
                               metavar='<tenantname>',
                               dest='tenant',
                               help='Name of tenant')
    delete_parser.add_argument('-forcedelete', '-fd',
                               dest='forcedelete',
                               help='Force delete Quotadirectory',
                               action='store_true')
    delete_parser.add_argument('-synchronous', '-sync',
                               dest='synchronous',
                               help='Synchronous Quotadirectory delete',
                               default=0,type=int)
    delete_parser.set_defaults(func=quotadirectory_delete)


def quotadirectory_delete(args):
    obj = QuotaDirectory(args.ip, args.port)
    try:
        resourceUri = obj.storageResource_query(
            args.filesystem,
            args.project,
            args.tenant)

        obj.delete(resourceUri, args.name, args.forcedelete, args.synchronous)

    except SOSError as e:
        if (e.err_code == SOSError.SOS_FAILURE_ERR):
            raise SOSError(
                SOSError.SOS_FAILURE_ERR,
                "QuotaDirectory: " +
                args.name +
                ", Delete Failed\n" +
                e.err_text)
        else:
            common.format_err_msg_and_raise(
                "delete",
                "quotadirectory",
                e.err_text,
                e.err_code)


'''
Update quotadirectory Parser
'''


def update_parser(subcommand_parsers, common_parser):
    # update command parser
    update_parser = subcommand_parsers.add_parser(
        'update',
        description='ViPR Quotadirectory update CLI usage',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Updates Quota directory')

    mandatory_args = update_parser.add_argument_group('mandatory arguments')
    mandatory_args.add_argument(
        '-n', '-name',
        help='Name of the Quota Directory',
        metavar='<name>',
        dest='name',
        required=True)
    mandatory_args.add_argument(
        '-fs', '-filesystem',
        help='Name of the Filesystem',
        metavar='<filesystem>',
        dest='filesystem',
        required=True)
    mandatory_args.add_argument('-project', '-pr',
                                metavar='<projectname>',
                                dest='project',
                                help='Name of Project',
                                required=True)
    update_parser.add_argument('-tenant', '-tn',
                               metavar='<tenantname>',
                               dest='tenant',
                               help='Name of tenant')
    update_parser.add_argument('-size', '-s',
                               metavar='<size>',
                               dest='size',
                               help='Size of Quotadirectory')

    update_parser.add_argument('-oplk', '-oplock ',
                               choices=["true", "false"],
                               metavar='<oplock>',
                               dest='oplock',
                               help='Oplock for Quotadirectory')

    update_parser.add_argument('-secsy', '-securitystyle',
                               choices=["unix", "ntfs", "mixed"],
                               help='Quota Directory Security Style ',
                               dest='securitystyle',
                               metavar='<securitystyle>')
    update_parser.add_argument('-synchronous', '-sync',
                               dest='synchronous',
                               help='Synchronous quotadirectory update',
                               default=0,type=int)
    update_parser.set_defaults(func=quotadirectory_update)




def quotadirectory_update(args):
    obj = QuotaDirectory(args.ip, args.port)
    try:
        resourceUri = obj.storageResource_query(
            args.filesystem,
            args.project,
            args.tenant)

        obj.update(resourceUri, args.name, args.size, args.oplock, 
		   args.securitystyle, args.synchronous)

    except SOSError as e:
        if (e.err_code == SOSError.SOS_FAILURE_ERR):
            raise SOSError(
                SOSError.SOS_FAILURE_ERR,
                "QuotaDirectory: " +
                args.name +
                ", Update Failed\n" +
                e.err_text)
        else:
            common.format_err_msg_and_raise(
                "update",
                "quotadirectory",
                e.err_text,
                e.err_code)


#
# Quota Directory Main parser routine
#


def quotadirectory_parser(parent_subparser, common_parser):

    parser = parent_subparser.add_parser(
        'quotadirectory',
        description='ViPR quotadirectory CLI usage',
        parents=[common_parser],
        conflict_handler='resolve',
        help='Operations on quotadirectory')

    subcommand_parsers = parser.add_subparsers(help='use one of sub-commands')

    # create command parser
    create_parser(subcommand_parsers, common_parser)

    # list command parser
    list_parser(subcommand_parsers, common_parser)

    # show parser
    show_parser(subcommand_parsers, common_parser)

    # delete parser
    delete_parser(subcommand_parsers, common_parser)

    # update parser
    update_parser(subcommand_parsers, common_parser)

