#!/bin/sh

KINDCLUSTERNAME=kcc-dev

kind delete cluster -n $KINDCLUSTERNAME
