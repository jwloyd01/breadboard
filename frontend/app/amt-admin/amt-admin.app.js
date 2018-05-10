import './amt-admin.service';
import './amt-admin.directive';
import 'jquery';
import 'bootstrap';
import './amt-admin.style.css';
import './manage-qualifications/manage-qualifications.directive';

angular.module('breadboard.amt-admin', [
  'breadboard.amt-admin.services',
  'ui.bootstrap.pagination',
  'ngFileUpload',
  'breadboard.amt-admin.manage-qualifications'
]);
