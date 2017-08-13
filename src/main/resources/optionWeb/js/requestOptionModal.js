"use strict";

angular.module('demoAppModule').controller('RequestOptionModalCtrl', function($http, $uibModalInstance, $uibModal, apiBaseURL, peers) {
    const requestOptionModal = this;

    requestOptionModal.peers = peers;
    requestOptionModal.optionTypes = ["CALL", "PUT"];
    requestOptionModal.form = {};
    requestOptionModal.formError = false;

    /** Validate and request an Option. */
    requestOptionModal.create = () => {
        if (invalidFormInput()) {
            requestOptionModal.formError = true;
        } else {
            requestOptionModal.formError = false;

            const strike = requestOptionModal.form.strike;
            const currency = requestOptionModal.form.currency;
            const expiry = requestOptionModal.form.expiry;
            const underlying = requestOptionModal.form.underlying;
            const party = requestOptionModal.form.issuer;
            const optionType = requestOptionModal.form.optionType;

            $uibModalInstance.close();

            // We define the Option creation endpoint.
            const requestOptionEndpoint =
                apiBaseURL +
                `request-option?strike=${strike}&currency=${currency}&expiry=${expiry}&underlying=${underlying}&issuer=${party}&optionType=${optionType}`;

            // We hit the endpoint to request the Option and handle success/failure responses.
            $http.get(requestOptionEndpoint).then(
                (result) => requestOptionModal.displayMessage(result),
                (result) => requestOptionModal.displayMessage(result)
            );
        }
    };

    /** Displays the success/failure response from attempting to request an Option. */
    requestOptionModal.displayMessage = (message) => {
        const requestOptionMsgModal = $uibModal.open({
            templateUrl: 'requestOptionMsgModal.html',
            controller: 'requestOptionMsgModalCtrl',
            controllerAs: 'requestOptionMsgModal',
            resolve: {
                message: () => message
            }
        });

        // No behaviour on close / dismiss.
        requestOptionMsgModal.result.then(() => {}, () => {});
    };

    /** Closes the Option creation modal. */
    requestOptionModal.cancel = () => $uibModalInstance.dismiss();

    // Validates the Option.
    function invalidFormInput() {
        return isNaN(requestOptionModal.form.strike) || (requestOptionModal.form.issuer === undefined) || (requestOptionModal.form.underlying === undefined)
            || (requestOptionModal.form.currency === undefined) || (requestOptionModal.form.optionType === undefined);
        ;
    }
});

// Controller for the success/fail modal.
angular.module('demoAppModule').controller('requestOptionMsgModalCtrl', function($uibModalInstance, message) {
    const requestOptionMsgModal = this;
    requestOptionMsgModal.message = message.data;
});